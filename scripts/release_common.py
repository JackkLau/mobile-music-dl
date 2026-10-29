"""Public release identity and bounded artifact metadata validation helpers."""
import re
from pathlib import Path

PACKAGE = "app.tingyu"
# The existing 0.1.1 release certificate; rotation requires an explicit migration decision.
CERTIFICATE_SHA256 = "ba4e5c4ba00be09d10421edac7f276f44393f52950623e64096f93c864b81911"
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def version_info(project):
    source = (Path(project) / "app/build.gradle.kts").read_text()
    names = re.findall(r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', source, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*([1-9][0-9]*)\s*$", source, re.M)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected one literal release versionName and versionCode")
    return names[0], int(codes[0])


def validate_ref(ref, version):
    if ref != "refs/heads/master" and ref != f"refs/tags/v{version}":
        raise ValueError("Release must use master or a version tag matching versionName")


def properties_value(value):
    """Serialize one Java Properties value without allowing a newline/property injection."""
    result = []
    encoded = value.encode("utf-16-be")
    for byte_index in range(0, len(encoded), 2):
        code = int.from_bytes(encoded[byte_index:byte_index + 2], "big")
        char = chr(code)
        if char in "\\:=#! ":
            result.append("\\" + char)
        elif 32 <= code <= 126:
            result.append(char)
        else:
            result.append(f"\\u{code:04x}")
    return "".join(result)


def parse_apk_badging(text):
    first = next((line for line in text.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", first))
    if set(fields) != {"name", "versionCode", "versionName"}:
        raise ValueError("APK package metadata missing")
    if "application-debuggable" in text:
        raise ValueError("Debuggable APK cannot be released")
    return fields["name"], fields["versionName"], int(fields["versionCode"])


def parse_apk_certificate(text):
    digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.M)
    if len(digests) != 1 or digests[0].lower() != CERTIFICATE_SHA256:
        raise ValueError("APK certificate differs from the existing TingYu release certificate")


def protobuf_fields(data):
    """Read bounded protobuf wire fields; no schema/package download is required.

    AAB XML schema: AOSP tools/aapt2/Resources.proto, XmlNode / XmlElement / XmlAttribute.
    Unknown fields are skipped; malformed/truncated/group wire data fails closed.
    """
    offset = 0

    def varint():
        nonlocal offset
        result = 0
        for shift in range(0, 70, 7):
            if offset >= len(data):
                raise ValueError("Truncated protobuf value")
            byte = data[offset]
            offset += 1
            result |= (byte & 127) << shift
            if byte < 128:
                return result
        raise ValueError("Invalid protobuf value")

    fields = {}
    while offset < len(data):
        tag = varint()
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value = varint()
        elif wire in (1, 2, 5):
            size = varint() if wire == 2 else (8 if wire == 1 else 4)
            if size > len(data) - offset:
                raise ValueError("Truncated protobuf field")
            value = data[offset:offset + size]
            offset += size
        else:
            raise ValueError("Unsupported protobuf wire type")
        fields.setdefault(number, []).append(value)
    return fields


def one_field(fields, key, default=b""):
    values = fields.get(key, [default])
    if len(values) != 1:
        raise ValueError("Duplicate singular protobuf field")
    return values[0]


def bundle_manifest(data):
    if len(data) > 2 * 1024 * 1024:
        raise ValueError("Bundle manifest is unexpectedly large")

    def element(node, depth=0):
        if depth > 64:
            raise ValueError("Bundle manifest nesting is too deep")
        node_fields = protobuf_fields(node)
        if 1 not in node_fields:  # XML text/whitespace node
            return None
        fields = protobuf_fields(one_field(node_fields, 1))
        attrs = {}
        for raw in fields.get(4, []):
            attr = protobuf_fields(raw)
            key = (one_field(attr, 1).decode(), one_field(attr, 2).decode())
            if key in attrs:
                raise ValueError("Duplicate bundle manifest attribute")
            attrs[key] = one_field(attr, 3).decode()
        children = [child for raw in fields.get(5, []) if (child := element(raw, depth + 1))]
        return one_field(fields, 3).decode(), attrs, children

    root = element(data)
    if not root or root[0] != "manifest":
        raise ValueError("Missing bundle manifest root")
    name, attrs, children = root
    apps = [child for child in children if child[0] == "application"]
    if len(apps) != 1 or apps[0][1].get((ANDROID_NS, "debuggable"), "false") != "false":
        raise ValueError("Bundle application is missing or debuggable")

    def inspect(node):
        if node[1].get((ANDROID_NS, "name"), "").endswith(".debug.ProbeActivity"):
            raise ValueError("Debug probe entry is present in release bundle")
        for child in node[2]:
            inspect(child)
    inspect(root)
    return (attrs.get(("", "package")), attrs.get((ANDROID_NS, "versionName")),
            int(attrs.get((ANDROID_NS, "versionCode"), "0")))
