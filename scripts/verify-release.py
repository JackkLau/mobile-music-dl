#!/usr/bin/env python3
"""Verify final APK/AAB identity/signatures before assembling public release assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

from release_common import (PACKAGE, CERTIFICATE_SHA256, bundle_manifest,
                            parse_apk_badging, parse_apk_certificate, version_info)


def run(command):
    result = subprocess.run([str(part) for part in command], capture_output=True, text=True, timeout=180)
    if result.returncode:
        raise ValueError(f"Release verification command failed: {Path(str(command[0])).name}")
    return result.stdout


def verify(project, apk, aab, version, code, output):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or code <= 0:
        raise ValueError("Invalid expected release version")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    build_tools = Path(sdk) / "build-tools/35.0.0"
    identity = (PACKAGE, version, code)
    if not apk.is_file() or not aab.is_file():
        raise ValueError("Both release APK and AAB must exist")
    signature = run([build_tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
    parse_apk_certificate(signature)
    if parse_apk_badging(run([build_tools / "aapt", "dump", "badging", apk])) != identity:
        raise ValueError("APK package/version differs from release metadata")
    xml = run([build_tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml"])
    if "ProbeActivity" in xml or re.search(r"android:(?:debuggable|testOnly).*0xffffffff", xml):
        raise ValueError("APK contains debug-only settings or diagnostic entry")
    run([build_tools / "zipalign", "-c", "-P", "16", "4", apk])
    with zipfile.ZipFile(aab) as archive:
        manifest = archive.getinfo("base/manifest/AndroidManifest.xml")
        if manifest.file_size > 2 * 1024 * 1024:
            raise ValueError("Bundle manifest is unexpectedly large")
        if bundle_manifest(archive.read(manifest)) != identity:
            raise ValueError("AAB package/version differs from release metadata")
    run(["java", Path(project) / "scripts/VerifySignedBundle.java", aab, CERTIFICATE_SHA256])
    output.mkdir(parents=True, exist_ok=True)
    assets = []
    for source, suffix in ((apk, "apk"), (aab, "aab")):
        target = output / f"tingyu-{version}-release.{suffix}"
        shutil.copyfile(source, target)
        checksum = hashlib.sha256()
        with target.open("rb") as file:
            for block in iter(lambda: file.read(1024 * 1024), b""):
                checksum.update(block)
        digest = checksum.hexdigest()
        assets.append({"file": target.name, "bytes": target.stat().st_size, "sha256": digest})
    sha = run(["git", "-C", project, "rev-parse", "HEAD"]).strip()
    metadata = {"package": PACKAGE, "versionName": version, "versionCode": code,
                "certificateSha256": CERTIFICATE_SHA256, "commit": sha, "assets": assets,
                "validation": ["APK signature and identity", "APK non-debuggable and no probe entry",
                               "APK ZIP alignment", "AAB manifest identity", "all AAB payload signatures"]}
    (output / "release.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['file']}\n" for item in assets))
    print(f"Verified {PACKAGE} {version} ({code}); APK/AAB use the existing release certificate.")
    print(f"Public assets: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version")
    parser.add_argument("--code", type=int)
    parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-release.apk"))
    parser.add_argument("--aab", type=Path, default=Path("app/build/outputs/bundle/release/app-release.aab"))
    parser.add_argument("--output", type=Path, default=Path("artifacts/github-release"))
    options = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    try:
        default_version, default_code = version_info(project)
        verify(project, options.apk, options.aab, options.version or default_version,
               options.code or default_code, options.output)
    except Exception as error:
        print(f"Release verification failed: {error}", file=sys.stderr)
        sys.exit(1)
