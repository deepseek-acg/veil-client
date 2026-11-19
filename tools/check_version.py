#!/usr/bin/env python3
"""Check that the built jar, gradle.properties and (optionally) the git tag all name one version.

    python3 tools/check_version.py --jar-dir build/libs            # CI: after ./gradlew build
    python3 tools/check_version.py --jar-dir build/libs --tag v0.1.11
    python3 tools/check_version.py --jar-dir "$V/build/libs" --project .   # local check

Kept as a file rather than an inline heredoc in the workflow: a heredoc inside a YAML `run: |`
block keeps the block's indentation, which Python rejects as an unexpected indent.
"""

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default=".", help="directory holding gradle.properties")
    parser.add_argument("--jar-dir", default="build/libs")
    parser.add_argument("--tag", help="git tag being built, e.g. v0.1.11")
    args = parser.parse_args()

    props = (Path(args.project) / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"(?m)^mod_version=(.+)$", props)
    if not match:
        sys.exit("no mod_version in gradle.properties")
    version = match.group(1).strip()

    jar_dir = Path(args.jar_dir)
    jar = jar_dir / f"mist-client-{version}.jar"
    if not jar.exists():
        found = sorted(path.name for path in jar_dir.glob("*.jar")) if jar_dir.exists() else []
        sys.exit(f"no {jar.name} in {jar_dir} (found: {found or 'nothing'})")

    with zipfile.ZipFile(jar) as archive:
        mod_version = json.loads(archive.read("fabric.mod.json"))["version"]
    if mod_version != version:
        sys.exit(f"{jar.name} declares version {mod_version}, gradle.properties says {version}")

    if args.tag:
        tag_version = args.tag[1:] if args.tag.startswith("v") else args.tag
        if tag_version != version:
            sys.exit(f"tag {args.tag} does not match version {version}; a release from this tag "
                     f"would be mislabelled")

    print(f"ok: {jar.name} version {mod_version}"
          + (f", tag {args.tag}" if args.tag else ""))


if __name__ == "__main__":
    main()
