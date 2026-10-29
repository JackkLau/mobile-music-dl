"""Release boundary regression tests; no real signing key or remote service is used."""
import base64
import hashlib
import importlib.util
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import release_common as common

spec = importlib.util.spec_from_file_location("prepare_signing", SCRIPTS / "prepare-release-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out) + bytes([value])


def message(field, value):
    raw = value.encode() if isinstance(value, str) else value
    return varint(field * 8 + 2) + varint(len(raw)) + raw


def attribute(name, value, namespace=""):
    return message(4, message(1, namespace) + message(2, name) + message(3, value))


def xml_node(name, attributes=b"", children=()):
    return message(1, message(3, name) + attributes + b"".join(message(5, child) for child in children))


def manifest(debuggable="false", activity="app.tingyu.MainActivity", version="0.1.2", code="3"):
    app = xml_node("application", attribute("debuggable", debuggable, common.ANDROID_NS),
        [xml_node("activity", attribute("name", activity, common.ANDROID_NS))])
    return xml_node("manifest", attribute("package", "app.tingyu") +
        attribute("versionName", version, common.ANDROID_NS) +
        attribute("versionCode", code, common.ANDROID_NS), [app])


class ReleaseBoundaryTest(unittest.TestCase):
    def test_version_source_requires_one_literal_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "app/build.gradle.kts"
            target.parent.mkdir()
            target.write_text('versionCode = 3\nversionName = "0.1.2"\n')
            self.assertEqual(("0.1.2", 3), common.version_info(directory))
            target.write_text('versionCode = 3\nversionName = "0.1.2"\nversionName = "9.9.9"\n')
            with self.assertRaises(ValueError): common.version_info(directory)

    def test_only_master_or_matching_release_tag_is_allowed(self):
        for allowed in ("refs/heads/master", "refs/tags/v0.1.2"):
            common.validate_ref(allowed, "0.1.2")
        for rejected in ("refs/heads/untrusted", "refs/pull/1/merge", "refs/tags/v0.1.1", ""):
            with self.subTest(ref=rejected), self.assertRaises(ValueError):
                common.validate_ref(rejected, "0.1.2")

    def test_apk_metadata_detects_debuggable_and_wrong_certificate(self):
        badging = "package: name='app.tingyu' versionCode='3' versionName='0.1.2' platformBuildVersionName='15' compileSdkVersionCodename='15'\n"
        self.assertEqual(("app.tingyu", "0.1.2", 3), common.parse_apk_badging(badging))
        with self.assertRaises(ValueError): common.parse_apk_badging(badging + "application-debuggable\n")
        cert = f"Signer #1 certificate SHA-256 digest: {common.CERTIFICATE_SHA256}\n"
        common.parse_apk_certificate(cert)
        for rejected in ("", cert.replace(common.CERTIFICATE_SHA256, "0" * 64), cert + cert.replace("#1", "#2")):
            with self.assertRaises(ValueError): common.parse_apk_certificate(rejected)

    def test_bundle_metadata_is_read_from_actual_protobuf_structure(self):
        self.assertEqual(("app.tingyu", "0.1.2", 3), common.bundle_manifest(manifest()))
        self.assertEqual(("app.tingyu", "0.1.1", 2), common.bundle_manifest(manifest(version="0.1.1", code="2")))

    def test_bundle_debug_flag_and_probe_entry_are_rejected(self):
        for value in (manifest(debuggable="true"), manifest(activity="app.tingyu.debug.ProbeActivity")):
            with self.assertRaises(ValueError): common.bundle_manifest(value)

    def test_malformed_or_duplicate_bundle_fields_fail_closed(self):
        for data in (b"\x00", b"\x0a\xff", b"\x0b", b"\x80" * 12, manifest()[:-1], manifest() * 2):
            with self.subTest(data=data[:12]), self.assertRaises(ValueError): common.bundle_manifest(data)

    def test_password_serialization_cannot_add_properties(self):
        value = " secret\\:=#!\nstoreFile=evil\r\t密码😀"
        escaped = common.properties_value(value)
        self.assertNotIn("\n", escaped)
        self.assertNotIn("\r", escaped)
        self.assertNotIn("\t", escaped)
        self.assertTrue(escaped.startswith("\\ secret\\\\\\:\\=\\#\\!\\u000astoreFile\\=evil"))
        self.assertIn("\\u5bc6\\u7801\\ud83d\\ude00", escaped)


class PrepareSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, TINGYU_KEYSTORE_BASE64=base64.b64encode(b"test-keystore" * 100).decode(),
            TINGYU_STORE_PASSWORD="PRIVATE_PASSWORD\nstoreFile=evil", TINGYU_KEY_ALIAS="test-alias",
            TINGYU_KEY_PASSWORD="PRIVATE_KEY_PASSWORD")

    def tearDown(self): self.temp.cleanup()

    def test_missing_secrets_fail_without_creating_files(self):
        with self.assertRaisesRegex(ValueError, "Missing required GitHub Actions secrets"):
            signing.prepare(self.root, {})
        self.assertEqual([], list(self.root.iterdir()))

    def test_invalid_base64_fails_without_echoing_values(self):
        env = self.env | {"TINGYU_KEYSTORE_BASE64": "PRIVATE_INVALID!"}
        with self.assertRaises(ValueError) as failure: signing.prepare(self.root, env)
        self.assertNotIn("PRIVATE", str(failure.exception))
        self.assertEqual([], list(self.root.iterdir()))

    def test_wrong_certificate_removes_temporary_key_and_does_not_write_passwords(self):
        result = subprocess.CompletedProcess([], 0, stdout=b"different-public-certificate", stderr=b"")
        with patch.object(signing.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(ValueError, "does not match"):
                signing.prepare(self.root, self.env)
        self.assertFalse((self.root / ".tooling/signing/tingyu-release.keystore").exists())
        self.assertFalse((self.root / "signing.properties").exists())

    def test_keytool_error_is_redacted_and_cleaned(self):
        result = subprocess.CompletedProcess([], 1, stdout=b"PRIVATE_OUTPUT", stderr=b"PRIVATE_ERROR")
        with patch.object(signing.subprocess, "run", return_value=result):
            with self.assertRaises(ValueError) as failure: signing.prepare(self.root, self.env)
        self.assertNotIn("PRIVATE", str(failure.exception))
        self.assertFalse((self.root / ".tooling/signing/tingyu-release.keystore").exists())

    def test_existing_signing_file_is_never_overwritten(self):
        config = self.root / "signing.properties"
        config.write_text("existing")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            signing.prepare(self.root, self.env)
        self.assertEqual("existing", config.read_text())

    def test_success_writes_private_permissions_and_escaped_properties(self):
        public_cert = b"public test certificate only"
        result = subprocess.CompletedProcess([], 0, stdout=public_cert, stderr=b"")
        with patch.object(signing, "CERTIFICATE_SHA256", hashlib.sha256(public_cert).hexdigest()), \
                patch.object(signing.subprocess, "run", return_value=result) as command:
            signing.prepare(self.root, self.env)
        self.assertIn("-storepass:env", command.call_args.args[0])
        self.assertNotIn(self.env["TINGYU_STORE_PASSWORD"], command.call_args.args[0])
        config = self.root / "signing.properties"
        key = self.root / ".tooling/signing/tingyu-release.keystore"
        self.assertEqual(0o600, config.stat().st_mode & 0o777)
        self.assertEqual(0o600, key.stat().st_mode & 0o777)
        self.assertEqual(0o700, key.parent.stat().st_mode & 0o777)
        self.assertEqual(4, len(config.read_text().splitlines()))


if __name__ == "__main__": unittest.main()
