#!/usr/bin/env python3
"""Restore only the existing release key from Actions secrets. Never print credential values."""
import base64
import binascii
import hashlib
import os
from pathlib import Path
import subprocess
import sys

from release_common import CERTIFICATE_SHA256, properties_value

SECRET_NAMES = ("TINGYU_KEYSTORE_BASE64", "TINGYU_STORE_PASSWORD", "TINGYU_KEY_ALIAS", "TINGYU_KEY_PASSWORD")


def prepare(project, env):
    missing = [name for name in SECRET_NAMES if not env.get(name)]
    if missing:
        raise ValueError("Missing required GitHub Actions secrets: " + ", ".join(missing))
    try:
        data = base64.b64decode("".join(env["TINGYU_KEYSTORE_BASE64"].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("TINGYU_KEYSTORE_BASE64 is not valid base64") from None
    if not 512 <= len(data) <= 49152:
        raise ValueError("Release keystore has an unexpected size")
    root = Path(project)
    key_dir = root / ".tooling/signing"
    key_path = key_dir / "tingyu-release.keystore"
    config = root / "signing.properties"
    if any(path.is_symlink() for path in (root / ".tooling", key_dir, key_path, config)):
        raise ValueError("Refusing symlinked signing paths")
    if key_path.exists() or config.exists():
        raise ValueError("Refusing to overwrite existing signing material")
    key_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    key_dir.chmod(0o700)
    created = []
    try:
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created.append(key_path)
        with os.fdopen(fd, "wb") as file:
            file.write(data)
        # The password is read from the environment, never exposed in argv or logs.
        cert = subprocess.run(["keytool", "-exportcert", "-alias", env["TINGYU_KEY_ALIAS"],
            "-keystore", str(key_path), "-storepass:env", "TINGYU_STORE_PASSWORD"],
            env=dict(env), capture_output=True, timeout=30)
        if cert.returncode != 0:
            raise ValueError("Cannot read the existing release certificate; check signing secrets")
        if hashlib.sha256(cert.stdout).hexdigest() != CERTIFICATE_SHA256:
            raise ValueError("Keystore does not match the existing 0.1.1 release certificate")
        values = {"storeFile": ".tooling/signing/tingyu-release.keystore",
                  "storePassword": env["TINGYU_STORE_PASSWORD"],
                  "keyAlias": env["TINGYU_KEY_ALIAS"], "keyPassword": env["TINGYU_KEY_PASSWORD"]}
        fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created.append(config)
        with os.fdopen(fd, "w", encoding="ascii") as file:
            file.write("".join(f"{key}={properties_value(value)}\n" for key, value in values.items()))
    except BaseException:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    try:
        prepare(Path(__file__).resolve().parents[1], os.environ)
    except ValueError as error:
        print(f"Release signing setup failed: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Release signing setup failed; signing material was not retained.", file=sys.stderr)
        sys.exit(1)
    print("Existing TingYu release certificate verified; temporary signing files prepared.")
