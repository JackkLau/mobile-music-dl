#!/usr/bin/env python3
"""Export the README icon directly from the Android brand resources."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DEST = ROOT / "docs/assets/weile-icon.svg"


def render_icon():
    mark = ET.parse(RES / "drawable/ic_brand_mark.xml").getroot()
    color_map = {node.attrib["name"]: node.text for node in
                 ET.parse(RES / "values/brand_colors.xml").findall("color")}
    name = ET.parse(RES / "values/strings.xml").findtext("string[@name='app_name']")
    width = mark.attrib[ANDROID + "viewportWidth"]
    height = mark.attrib[ANDROID + "viewportHeight"]
    svg = ET.Element("svg", xmlns="http://www.w3.org/2000/svg", width="512", height="512",
                     viewBox=f"0 0 {width} {height}", role="img", attrib={"aria-labelledby": "title"})
    ET.SubElement(svg, "title", id="title").text = f"{name} · 微笑音符"
    ET.SubElement(svg, "rect", width=width, height=height, rx=f"{float(width) / 4:g}",
                  fill=color_map["brand_background"])
    for path in mark:
        if path.tag != "path":
            raise ValueError("Unsupported brand vector element: " + path.tag)
        color = path.attrib[ANDROID + "fillColor"].removeprefix("@color/")
        rule = {"evenOdd": "evenodd", "nonZero": "nonzero"}[path.get(ANDROID + "fillType", "nonZero")]
        ET.SubElement(svg, "path", fill=color_map[color], attrib={"fill-rule": rule},
                      d=path.attrib[ANDROID + "pathData"])
    ET.indent(svg, space="  ")
    return ET.tostring(svg, encoding="unicode") + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="detect outdated README graphics")
    args = parser.parse_args()
    result = render_icon()
    if args.check:
        if not DEST.is_file() or result != DEST.read_text(encoding="utf-8"):
            raise SystemExit("README icon is out of date. Run python3 scripts/export-brand.py")
    else:
        DEST.write_text(result, encoding="utf-8")
