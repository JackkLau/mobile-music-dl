import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch
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

    def test_release_build_and_publisher_share_the_same_default(self):
        (self.root / "gradle.properties").write_text("officialGiteeRepository=team/mirror\n")
        self.assertEqual("team/mirror", mirror.configured_repository(self.root, {"GITEE_REPOSITORY": ""}))
        self.assertEqual("team/alternative", mirror.configured_repository(self.root, {"GITEE_REPOSITORY": "team/alternative"}))

    def test_bootstrap_verifies_owner_and_absence_then_requests_public_initialized_repo(self):
        client = mirror.Gitee("iamjacky/mobile-music-dl", "testing")
        client.request = Mock(side_effect=[None, {"login": "iamjacky"}, None, self.repo, self.repo])
        self.assertEqual("main", client.public_branch())
        path = "/repos/iamjacky/mobile-music-dl"
        self.assertEqual([
            call(path, optional=True), call("/user", authenticated=True),
            call(path, authenticated=True, optional=True),
            call("/user/repos", payload={"name": "mobile-music-dl", "path": "mobile-music-dl", "public": 1, "auto_init": True}),
            call(path, optional=True),
        ], client.request.call_args_list)

    def test_wrong_account_private_repo_and_other_namespace_are_never_modified(self):
        cases = [
            ("iamjacky/mobile-music-dl", [None, {"login": "other-account"}]),
            ("iamjacky/mobile-music-dl", [None, {"login": "iamjacky"}, {"private": True}]),
            ("iamjacky/other", [None]), ("other/mobile-music-dl", [None]),
            ("iamjacky/mobile-music-dl", [{"private": False, "default_branch": None}]),
        ]
        for repo, responses in cases:
            with self.subTest(repo=repo, responses=responses):
                client = mirror.Gitee(repo, "testing")
                client.request = Mock(side_effect=responses)
                with self.assertRaises(ValueError):
                    client.public_branch()
                self.assertTrue(all("payload" not in c.kwargs for c in client.request.call_args_list))

    def test_public_review_or_create_failure_blocks_release_until_readable(self):
        client = mirror.Gitee("iamjacky/mobile-music-dl", "testing")
        unavailable = HTTPError("", 422, "", {}, None)
        client.request = Mock(side_effect=[None, {"login": "iamjacky"}, None, unavailable, None])
        with self.assertRaisesRegex(ValueError, "public review"):
            client.public_branch()
        client.request = Mock(side_effect=[None, {"login": "iamjacky"}, None, {"private": True}, None])
        with self.assertRaisesRegex(ValueError, "review status"):
            client.public_branch()
        client.request = Mock(side_effect=[self.repo])
        self.assertEqual("main", client.public_branch())
        client.request.assert_called_once_with("/repos/iamjacky/mobile-music-dl", optional=True)

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

    @patch.object(mirror.subprocess, "run")
    def test_slow_upload_disables_expect_and_reports_only_transfer_metrics(self, run):
        self.client.upload(21, self.apk)
        args = run.call_args.args[0]
        self.assertEqual("2700", args[args.index("--max-time") + 1])
        self.assertIn("Expect:", args)
        self.assertEqual("Gitee multipart upload: %{size_upload} bytes in %{time_total} seconds\n",
                         args[args.index("--write-out") + 1])
        self.assertNotIn("testing", " ".join(args))
        self.assertNotIn("--retry", args)
        self.assertEqual(b"Authorization: Bearer testing\n", run.call_args.kwargs["input"])

    @patch.object(mirror.time, "sleep")
    def test_lost_upload_response_recovers_after_publication_delay(self, sleep):
        self.prepare_api(self.repo, self.release, self.release, self.release, self.attached)
        self.client.upload.side_effect = subprocess.CalledProcessError(28, ["curl"])
        self.publish()
        self.client.upload.assert_called_once()
        sleep.assert_called_once_with(10)
        self.client.verify_download.assert_called_once_with(self.tag, self.artifact)

    @patch.object(mirror.time, "sleep")
    def test_timed_out_upload_never_reposts_when_still_missing(self, sleep):
        self.prepare_api(self.repo, self.release, self.release, *[self.release] * 4)
        self.client.upload.side_effect = subprocess.CalledProcessError(28, ["curl"])
        with self.assertRaisesRegex(ValueError, "curl 28.*still missing"):
            self.publish()
        self.client.upload.assert_called_once()
        self.assertEqual([call(10), call(30), call(60)], sleep.call_args_list)
        self.client.verify_download.assert_not_called()

    @patch.object(mirror.time, "sleep")
    def test_successful_upload_can_precede_public_attachment_listing(self, sleep):
        self.prepare_api(self.repo, self.release, self.release, self.release, self.attached)
        self.publish()
        sleep.assert_called_once_with(10)
        self.client.upload.assert_called_once()
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
