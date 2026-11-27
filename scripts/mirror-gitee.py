#!/usr/bin/env python3
"""Mirror a verified public GitHub release to the configured public Gitee repository."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import urljoin, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, Request

from release_common import CERTIFICATE_SHA256, PACKAGE

GITHUB = "https://github.com/JackkLau/mobile-music-dl/releases/download"
COMPONENT = r"[A-Za-z0-9][A-Za-z0-9._-]*"
NUMBER = r"(?:0|[1-9][0-9]{0,8})"
MAX_JSON = 1024 * 1024


def version(tag):
    if not re.fullmatch(rf"v{NUMBER}\.{NUMBER}\.{NUMBER}", tag):
        raise ValueError("Expected a stable tag such as v0.1.14")
    return tuple(map(int, tag[1:].split(".")))


def named_asset(items, key, name):
    matches = [item for item in items if item.get(key) == name]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one asset named {name}")
    return matches[0]


def verify_bytes(stream, size, digest):
    count = 0
    checksum = hashlib.sha256()
    while chunk := stream.read(256 * 1024):
        count += len(chunk)
        if count > size:
            raise ValueError("Attachment exceeds the published size")
        checksum.update(chunk)
    if count != size or checksum.hexdigest() != digest:
        raise ValueError("Attachment does not match the published size and SHA-256")


def verify_source(directory, tag):
    version(tag)
    manifest_file = directory / "release.json"
    apk = directory / f"weile-{tag[1:]}-release.apk"
    if manifest_file.stat().st_size > MAX_JSON:
        raise ValueError("Unexpectedly large release manifest")
    github = json.loads((directory / "github.json").read_text())
    if github["tag_name"] != tag or github["draft"] is not False or github["prerelease"] is not False:
        raise ValueError("Expected a published stable GitHub release")
    body = manifest_file.read_text(encoding="utf-8")
    manifest = json.loads(body)
    if (manifest["package"], manifest["versionName"], manifest["certificateSha256"]) != (
            PACKAGE, tag[1:], CERTIFICATE_SHA256):
        raise ValueError("Unexpected release manifest identity")
    artifact = named_asset(manifest["assets"], "file", apk.name)
    for path in (manifest_file, apk):
        asset = named_asset(github["assets"], "name", path.name)
        size = asset["size"]
        digest = asset["digest"]
        if (asset["browser_download_url"] != f"{GITHUB}/{tag}/{path.name}"
                or not isinstance(size, int) or not 0 < size <= 256 * 1024 * 1024
                or not re.fullmatch("sha256:[0-9a-f]{64}", digest)):
            raise ValueError("Invalid GitHub asset metadata")
        sha = digest.split(":")[1]
        if path == apk and (artifact["bytes"], artifact["sha256"]) != (size, sha):
            raise ValueError("APK identity differs between release manifest and GitHub")
        with path.open("rb") as source:
            verify_bytes(source, size, sha)
    return apk, body, artifact


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Gitee:
    def __init__(self, repository, token):
        if not re.fullmatch(rf"{COMPONENT}/{COMPONENT}", repository):
            raise ValueError("Set GITEE_REPOSITORY to the official public owner/repo")
        if not token or any(c.isspace() for c in token):
            raise ValueError("Configure the GITEE_TOKEN secret for the mirror")
        self.repository = repository
        self.root = "https://gitee.com/api/v5/repos/" + repository
        self.token = token
        self.opener = build_opener(NoRedirect())

    def request(self, path="", payload=None, optional=False):
        request = Request(self.root + path, headers={"Accept": "application/json"})
        if payload is not None:
            request.add_header("Authorization", "Bearer " + self.token)
            request.add_header("Content-Type", "application/json")
            request.data = json.dumps(payload).encode()
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(MAX_JSON + 1)
        except HTTPError as error:
            if optional and error.code == 404:
                return None
            raise
        if len(raw) > MAX_JSON:
            raise ValueError("Unexpectedly large API response")
        return json.loads(raw)

    def upload(self, release_id, apk):
        if type(release_id) is not int or release_id <= 0:
            raise ValueError("Invalid Gitee release id")
        # curl streams multipart from disk. The credential goes over stdin, never in argv or a URL.
        subprocess.run(["curl", "--silent", "--show-error", "--fail", "--proto", "=https",
                        "--max-time", "450", "--header", "@-", "--output", os.devnull,
                        "--form", f"file=@{apk};type=application/vnd.android.package-archive",
                        f"{self.root}/releases/{release_id}/attach_files"],
                       input=f"Authorization: Bearer {self.token}\n".encode(), check=True)

    def public_url(self, tag, filename):
        return f"https://gitee.com/{self.repository}/releases/download/{tag}/{filename}"

    def verify_download(self, tag, artifact):
        url = self.public_url(tag, artifact["file"])
        for _ in range(5):
            try:
                with self.opener.open(url, timeout=90) as response:
                    if response.status != 200:
                        raise ValueError("Gitee did not return a complete public APK")
                    verify_bytes(response, artifact["bytes"], artifact["sha256"])
                    return
            except HTTPError as redirect:
                if redirect.code not in (301, 302, 303, 307, 308):
                    raise
                location = redirect.headers.get("Location")
                if not location:
                    raise ValueError("Missing Gitee attachment redirect")
                url = urljoin(url, location)
                target = urlsplit(url)
                if (target.scheme != "https" or target.hostname not in ("gitee.com", "foruda.gitee.com")
                        or target.port not in (None, 443) or target.username is not None
                        or target.password is not None):
                    raise ValueError("Unexpected Gitee attachment redirect")
                filename = re.escape(artifact["file"])
                if target.hostname == "gitee.com":
                    path = rf"/{re.escape(self.repository)}/attach_files/[0-9]+/download/{filename}"
                else:
                    path = rf"/attach_file/[0-9]+/{filename}"
                if not re.fullmatch(path, target.path):
                    raise ValueError("Attachment redirect points outside the configured Gitee release")
        raise ValueError("Too many Gitee attachment redirects")


def publish(gitee, tag, apk, body, artifact):
    project = gitee.request()  # Anonymous visibility is also required by the Android updater.
    branch = project["default_branch"]
    if project.get("private") is not False or not branch:
        raise ValueError("Gitee needs an initialized, publicly readable repository")
    latest = gitee.request("/releases/latest", optional=True)
    if latest and version(latest["tag_name"]) > version(tag):
        raise ValueError("A newer release is already on Gitee; refusing to change latest")
    release_path = "/releases/tags/" + tag
    release = gitee.request(release_path, optional=True)
    if release is None:
        release = gitee.request("/releases", payload={
            "tag_name": tag, "target_commitish": branch, "name": "微乐 " + tag[1:],
            "prerelease": False, "body": body,
        })
    if (release["tag_name"] != tag or release["prerelease"] is not False
            or json.loads(release["body"]) != json.loads(body)):
        raise ValueError("The Gitee release conflicts with the verified manifest")
    if not any(item.get("name") == apk.name for item in release["assets"]):
        gitee.upload(release["id"], apk)
    # Read the identical endpoints used by Android, without the publisher credential.
    current = gitee.request("/releases/latest")
    if (current["tag_name"] != tag or current["prerelease"] is not False
            or json.loads(current["body"]) != json.loads(body)):
        raise ValueError("Public Gitee latest does not match the intended release")
    attachment = named_asset(current["assets"], "name", apk.name)
    if attachment["browser_download_url"] != gitee.public_url(tag, apk.name):
        raise ValueError("Unexpected Gitee attachment URL")
    gitee.verify_download(tag, artifact)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        apk, body, artifact = verify_source(args.directory, args.tag)
        client = Gitee(os.environ.get("GITEE_REPOSITORY", ""), os.environ.get("GITEE_TOKEN", ""))
        publish(client, args.tag, apk, body, artifact)
        print(f"Verified public Gitee mirror: {client.public_url(args.tag, apk.name)}")
    except HTTPError as error:
        sys.exit(f"Gitee HTTP {error.code}; check public repository visibility, write permissions and attachment quota")
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Gitee mirror failed: {error}")
