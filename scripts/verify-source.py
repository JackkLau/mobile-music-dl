#!/usr/bin/env python3
"""Dependency-free structural checks. This is NOT a Kotlin compiler or runtime test."""
from pathlib import Path
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
checks = []

def check(condition, description):
    checks.append((bool(condition), description))

def check_kotlin(path):
    text = path.read_text()
    size = len(text)

    def string(i, quote):
        end = quote
        i += len(quote)
        while i < size:
            if text.startswith(end, i):
                return i + len(end)
            if quote != "'''" and quote != '"""' and text[i] == "\\":
                i += 2
                continue
            if quote != "'" and text.startswith("${", i):
                i = code(i + 2, "}")
                continue
            i += 1
        raise ValueError("unterminated literal")

    def code(i, closing=None):
        pairs = {"(": ")", "[": "]", "{": "}"}
        while i < size:
            if text.startswith("//", i):
                index = text.find("\n", i)
                i = size if index < 0 else index + 1
            elif text.startswith("/*", i):
                depth = 1
                i += 2
                while i < size and depth:
                    if text.startswith("/*", i): depth += 1; i += 2
                    elif text.startswith("*/", i): depth -= 1; i += 2
                    else: i += 1
                if depth: raise ValueError("unterminated comment")
            elif text.startswith('"""', i): i = string(i, '"""')
            elif text[i] in "\"'": i = string(i, text[i])
            elif text[i] in pairs: i = code(i + 1, pairs[text[i]])
            elif text[i] in ")]}" :
                if text[i] != closing: raise ValueError(f"unmatched {text[i]} at {i}")
                return i + 1
            else: i += 1
        if closing: raise ValueError(f"missing {closing}")
        return i

    code(0)
    return bool(re.search(r"^package app\.tingyu(?:\.[A-Za-z0-9_]+)*$", text, re.M))

source_root = ROOT / "app/src/main"
manifest = ET.parse(source_root / "AndroidManifest.xml").getroot()
ns = "{http://schemas.android.com/apk/res/android}"
application = manifest.find("application")
check(application is not None, "manifest application exists")
check(application.get(ns + "allowBackup") == "false", "account backup disabled")
check(application.get(ns + "usesCleartextTraffic") == "false", "cleartext traffic disabled")
permissions = {node.get(ns + "name") for node in manifest.findall("uses-permission")}
check("android.permission.MANAGE_EXTERNAL_STORAGE" not in permissions, "no broad storage permission")
check("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in permissions, "media foreground permission declared")
check("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions, "download foreground permission declared")
for node in list(application):
    if node.tag not in {"activity", "service", "receiver"}: continue
    name = node.get(ns + "name")
    qualified = "app.tingyu" + name if name.startswith(".") else name
    check((source_root / "java" / (qualified.replace(".", "/") + ".kt")).is_file(), "component exists: " + qualified)
for path in source_root.rglob("*.xml"):
    ET.parse(path)
    check(True, "XML parses: " + str(path.relative_to(ROOT)))
for path in (ROOT / "app/src").rglob("*.kt"):
    try: check(check_kotlin(path), "Kotlin lexical/package check: " + str(path.relative_to(ROOT)))
    except ValueError as error: check(False, str(path.relative_to(ROOT)) + ": " + str(error))
for path in (ROOT / "scripts").rglob("*.sh"):
    check(subprocess.run(["bash", "-n", str(path)]).returncode == 0, "shell syntax: " + path.name)
for required in ["DESIGN.md", "UX-CONTRACT.md", "README.md", "app/build.gradle.kts"]:
    check((ROOT / required).is_file(), "required file: " + required)
config = json.loads((ROOT / "premium-ui.json").read_text())
check(all((ROOT / value).is_file() for value in config["evidence"].values()), "UI test evidence files exist")
failed = [description for passed, description in checks if not passed]
summary = {
    "structural_checks": len(checks), "passed": len(checks) - len(failed), "failures": failed,
    "kotlin_unit_tests_written": sum(len(re.findall(r"@Test\b", p.read_text())) for p in (ROOT / "app/src/test").rglob("*.kt")),
    "android_tests_written": sum(len(re.findall(r"@Test\b", p.read_text())) for p in (ROOT / "app/src/androidTest").rglob("*.kt")),
    "limitation": "No Kotlin type checking, dependency resolution, APK build, media playback or Android runtime execution is performed by this script.",
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
sys.exit(1 if failed else 0)
