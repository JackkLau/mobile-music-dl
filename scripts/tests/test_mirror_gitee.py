import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("mirror_gitee", SCRIPTS / "mirror-gitee.py")
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.tag = "v0.1.15"
        self.apk = self.root / "weile-0.1.15-release.apk"
        self.apk.write_bytes(b"test release asset")
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.artifact = {"file": self.apk.name, "bytes": self.apk.stat().st_size, "sha256": digest}
        manifest = {"versionName": "0.1.15", "versionCode": 16, "package": mirror.PACKAGE,
                    "certificateSha256": mirror.CERTIFICATE_SHA256, "assets": [self.artifact]}
        self.body = json.dumps(manifest)
        manifest_file = self.root / "release.json"
        manifest_file.write_text(self.body)
        assets = [{"name": p.name, "digest": "sha256:" + hashlib.sha256(p.read_bytes()).hexdigest(),
                   "size": p.stat().st_size, "browser_download_url": f"{mirror.GITHUB}/{self.tag}/{p.name}"}
                  for p in [manifest_file, self.apk]]
        self.github = {"tag_name": self.tag, "draft": False, "prerelease": False, "assets": assets}
        (self.root / "github.json").write_text(json.dumps(self.github))
        self.client = mirror.Gitee("test/public-mirror", "testing")
        self.release = {"id": 21, "tag_name": self.tag, "prerelease": False, "body": self.body, "assets": []}
        self.attached = {**self.release, "assets": [
            {"name": self.apk.name, "browser_download_url": self.client.public_url(self.tag, self.apk.name)}]}
        self.repo = {"private": False, "default_branch": "main"}

    def test_manifest_apk_and_both_github_digests_agree(self):
        self.assertEqual((self.apk, self.body, self.artifact), mirror.verify_source(self.root, self.tag))
        self.apk.write_bytes(b"Z" * self.apk.stat().st_size)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            mirror.verify_source(self.root, self.tag)

    def test_unpublished_attacker_url_and_changed_manifest_are_rejected(self):
        for field in ("draft", "prerelease"):
            (self.root / "github.json").write_text(json.dumps({**self.github, field: True}))
            with self.assertRaisesRegex(ValueError, "stable"):
                mirror.verify_source(self.root, self.tag)
        self.github["assets"][1]["browser_download_url"] = "https://other.invalid/apk"
        (self.root / "github.json").write_text(json.dumps(self.github))
        with self.assertRaisesRegex(ValueError, "metadata"):
            mirror.verify_source(self.root, self.tag)
        (self.root / "release.json").write_text(self.body.replace(mirror.PACKAGE, "another.package"))
        with self.assertRaisesRegex(ValueError, "identity"):
            mirror.verify_source(self.root, self.tag)

    def test_tag_and_namespace_validation(self):
        for tag in ("v0.1.015", "v0.1.15/../x", "0.1.15", "v0.1.15-rc"):
            with self.assertRaises(ValueError):
                mirror.version(tag)
        for repo in ("https://gitee.com/a/b", "a/..", ".//r", "user/r?key=x", ""):
            with self.assertRaises(ValueError):
                mirror.Gitee(repo, "testing")
        with self.assertRaisesRegex(ValueError, "secret"):
            mirror.Gitee("owner/repo", "")

    def prepare_api(self, *responses):
        self.client.request = Mock(side_effect=responses)
        self.client.upload = Mock()
        self.client.verify_download = Mock()

    def publish(self):
        mirror.publish(self.client, self.tag, self.apk, self.body, self.artifact)

    def test_create_release_then_anonymously_verify(self):
        self.prepare_api(self.repo, None, None, self.release, self.attached)
        self.publish()
        creation = self.client.request.call_args_list[3]
        self.assertEqual(self.body, creation.kwargs["payload"]["body"])
        self.assertEqual("main", creation.kwargs["payload"]["target_commitish"])
        self.client.upload.assert_called_once_with(21, self.apk)
        self.client.verify_download.assert_called_once_with(self.tag, self.artifact)

    def test_retry_partial_and_already_published(self):
        for release in (self.release, self.attached):
            self.prepare_api(self.repo, release, release, self.attached)
            self.publish()
            self.assertEqual(not release["assets"], self.client.upload.called)
            self.client.verify_download.assert_called_once()
            self.assertTrue(all("payload" not in call.kwargs for call in self.client.request.call_args_list))

    def test_conflict_and_older_run_do_not_modify_existing_release(self):
        self.prepare_api(self.repo, {"tag_name": "v0.2.0"})
        with self.assertRaisesRegex(ValueError, "newer"):
            self.publish()
        self.client.upload.assert_not_called()
        self.prepare_api(self.repo, self.release, {**self.release, "body": "{}"})
        with self.assertRaisesRegex(ValueError, "conflicts"):
            self.publish()
        self.client.upload.assert_not_called()

    def test_existing_wrong_bytes_fail_without_overwrite(self):
        self.prepare_api(self.repo, self.attached, self.attached, self.attached)
        self.client.verify_download.side_effect = ValueError("Wrong digest")
        with self.assertRaisesRegex(ValueError, "digest"):
            self.publish()
        self.client.upload.assert_not_called()

    def test_public_readback_rejects_offsite_or_mismatched_files(self):
        for location in ("http://foruda.gitee.com/file", "https://foruda.gitee.com/attach_file/123/other.apk",
                         "https://gitee.com/foreign/repo/download/x",
                         "https://foruda.gitee.com/another-path/123/" + self.apk.name,
                         "https://foruda.gitee.com.attacker.example/file"):
            self.client.opener = Mock()
            self.client.opener.open.side_effect = HTTPError("", 302, "", {"Location": location}, None)
            with self.assertRaisesRegex(ValueError, "redirect"):
                self.client.verify_download(self.tag, self.artifact)
            self.client.opener.open.assert_called_once()
        self.client.opener = Mock()
        error_page = io.BytesIO(b"please log in")
        error_page.status = 200
        self.client.opener.open.return_value = error_page
        with self.assertRaisesRegex(ValueError, "size and SHA"):
            self.client.verify_download(self.tag, self.artifact)

    def test_public_readback_follows_real_gitee_attachment_layout(self):
        locations = [f"/test/public-mirror/attach_files/123/download/{self.apk.name}",
                     f"https://foruda.gitee.com/attach_file/456/{self.apk.name}?signature=example"]
        file = io.BytesIO(self.apk.read_bytes())
        file.status = 200
        self.client.opener = Mock()
        self.client.opener.open.side_effect = [
            HTTPError("", 302, "", {"Location": u}, None) for u in locations] + [file]
        self.client.verify_download(self.tag, self.artifact)
