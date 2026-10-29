#!/usr/bin/env python3
"""Validate the trusted Git ref and export public release metadata for Actions."""
import os
from pathlib import Path
import subprocess
import sys

from release_common import version_info, validate_ref


if __name__ == "__main__":
    try:
        root = Path(__file__).resolve().parents[1]
        version, code = version_info(root)
        validate_ref(os.environ.get("GITHUB_REF", ""), version)
        subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD", "origin/master"], cwd=root,
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        output = os.environ.get("GITHUB_OUTPUT")
        if output:
            with open(output, "a", encoding="utf-8") as file:
                file.write(f"version={version}\ncode={code}\n")
        print(f"Release version {version} (code {code}); commit belongs to master history.")
    except Exception:
        print("Release preflight failed: use master or its matching version tag; commit must belong to master history.", file=sys.stderr)
        sys.exit(1)
