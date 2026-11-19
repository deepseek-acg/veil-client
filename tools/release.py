#!/usr/bin/env python3
"""Cut a veil-client release in one step.

    python3 tools/release.py --version 0.1.0 --notes release-notes/0.1.0.md [--dry-run]

Steps, in order, aborting at the first failure:

  1. check the tree is clean, on main, and that the version and tag are new
  2. write the new version into gradle.properties (not yet committed)
  3. copy the tree out of the repo path and build it there (the repo path can contain
     characters that break the JVM launcher, so builds never run in place)
  4. assert the built jar's fabric.mod.json version equals the version being released
  5. compute SHA-256 and size, and write the release body with that digest in it
  6. commit the bump, push main, tag, push the tag
  7. create the GitHub release, upload the single jar, read the asset digest back

Nothing is committed or tagged until the build succeeds and the jar reports the right version, which
is the mistake this script exists to prevent: earlier tags pointed at trees that did not compile, and
at a tree whose version did not match the tag name. A failed step 3 or 4 restores gradle.properties.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

REPO_SLUG = "deepseek-acg/veil-client"
EXCLUDES = {".git", "build", ".gradle", ".idea"}
AUTHOR = ["-c", "user.name=deepseek-acg", "-c", "user.email=deepseek-acg@users.noreply.github.com"]


def run(cmd, cwd=None, capture=True):
    result = subprocess.run(cmd, cwd=cwd, check=False, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.STDOUT if capture else None)
    return result.returncode, (result.stdout or "")


def git(*args, cwd=None, check=True):
    # stderr is kept out of the returned text: `git describe` on a tag-less repo prints its failure
    # to stderr, and merging the streams would turn that into a bogus "previous tag".
    result = subprocess.run(["git", *args], cwd=cwd, check=False, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if check and result.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed ({result.returncode})")
    return result.stdout.strip()


def property_value(props_path, key):
    with open(props_path, encoding="utf-8") as props:
        for line in props:
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip()
    sys.exit(f"no {key} in {props_path}")


def github_token(repo_dir):
    out = subprocess.run(["git", "credential", "fill"], cwd=repo_dir, check=True, text=True,
                         input="protocol=https\nhost=github.com\nusername=deepseek-acg\n\n",
                         stdout=subprocess.PIPE).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    sys.exit("no GitHub token from `git credential fill`")


def api(token, method, url, data=None, raw=None, content_type="application/json"):
    payload = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
    request = urllib.request.Request(url, data=payload, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", "application/vnd.github+json")
    if payload is not None:
        request.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode() or "{}")


def copy_tree(source, target):
    for root, dirs, files in os.walk(source):
        dirs[:] = [d for d in dirs if d not in EXCLUDES]
        for name in files:
            src = os.path.join(root, name)
            dst = os.path.join(target, os.path.relpath(src, source))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)


def read_version(props_path):
    return property_value(props_path, "mod_version")


def build(repo_dir, workdir_root, version, java_home):
    """Copy the tree out of the repo path and build it there. Returns (jar path, build log tail)."""
    workdir = tempfile.mkdtemp(prefix=f"veil-{version}-", dir=workdir_root)
    copy_tree(repo_dir, workdir)
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    # Windows runs the .bat wrapper: the shell script is not a valid Win32 executable there.
    if os.name == "nt":
        gradlew = os.path.join(workdir, "gradlew.bat")
    else:
        gradlew = os.path.join(workdir, "gradlew")
        os.chmod(gradlew, 0o755)
    code, output = run([gradlew, "build", "--console=plain"], cwd=workdir)
    if code != 0:
        return None, "\n".join(output.strip().splitlines()[-40:])
    for line in output.splitlines():
        if "verifyMixinTargets:" in line:
            print(line.strip())
    return os.path.join(workdir, "build", "libs", f"veil-client-{version}.jar"), output


def main():
    parser = argparse.ArgumentParser(description="Cut a veil-client release.")
    parser.add_argument("--version", required=True, help="release version, e.g. 0.1.0 (leading v is ignored)")
    parser.add_argument("--notes", help="markdown file whose contents go into the release body")
    parser.add_argument("--summary", default="", help="one-line summary at the top of the release body")
    parser.add_argument("--dry-run", action="store_true", help="build and verify, then stop before committing")
    args = parser.parse_args()

    version = args.version.lstrip("v")
    tag = f"v{version}"
    repo_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    jar_name = f"veil-client-{version}.jar"

    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        sys.exit("JAVA_HOME must point at a JDK 25")

    # 1 ── preconditions, before anything is written
    if git("status", "--porcelain", cwd=repo_dir):
        sys.exit("working tree is not clean")
    branch = git("rev-parse", "--abbrev-ref", "HEAD", cwd=repo_dir)
    if branch != "main":
        sys.exit(f"on branch {branch}, expected main")
    props_path = os.path.join(repo_dir, "gradle.properties")
    current = read_version(props_path)
    if current == version:
        sys.exit(f"mod_version is already {version}")
    if git("tag", "-l", tag, cwd=repo_dir):
        sys.exit(f"tag {tag} already exists")
    previous_tag = git("describe", "--tags", "--abbrev=0", cwd=repo_dir, check=False)

    # 2 ── write the version, keep the old text so a failure leaves no trace. Read and write with
    # newline="" and substitute one line in place, so restoring it later matches byte for byte
    # (rewriting the file with \n on a CRLF checkout leaves it looking modified).
    with open(props_path, encoding="utf-8", newline="") as props:
        original = props.read()
    bumped, replaced = re.subn(r"(?m)^mod_version=[^\r\n]*", f"mod_version={version}", original)
    if replaced != 1:
        sys.exit(f"expected one mod_version line in {props_path}, found {replaced}")
    with open(props_path, "w", encoding="utf-8", newline="") as props:
        props.write(bumped)

    try:
        # 3 ── build out of tree
        jar_path, output = build(repo_dir, os.environ.get("TEMP") or None, version, java_home)
        if jar_path is None:
            sys.exit(f"build failed, nothing committed or tagged:\n{output}")
        if not os.path.exists(jar_path):
            libs = os.path.dirname(jar_path)
            sys.exit(f"expected {jar_name}; build/libs holds {sorted(os.listdir(libs))}")

        # 4 ── the jar must be the version being released
        with zipfile.ZipFile(jar_path) as jar:
            built = json.loads(jar.read("fabric.mod.json"))["version"]
        if built != version:
            sys.exit(f"jar reports {built}, releasing {version}; nothing committed or tagged")
    except BaseException:
        # Whatever went wrong, the tree goes back to the version it had; nothing was committed.
        with open(props_path, "w", encoding="utf-8", newline="") as props:
            props.write(original)
        raise

    # 5 ── digest and body, still uncommitted
    data = open(jar_path, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    size = len(data)
    print(f"built {jar_name}: sha256 {sha}, {size} bytes")

    commits = git("log", "--oneline", f"{previous_tag}..HEAD", cwd=repo_dir) if previous_tag else ""
    notes = ""
    if args.notes:
        with open(os.path.join(repo_dir, args.notes), encoding="utf-8") as handle:
            notes = handle.read().strip()

    body = "\n\n".join(part for part in [
        args.summary or f"Veil {version} — Minecraft 26.3",
        "**Requires:** Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API 0.166.2+26.3, Java 25.",
        f"## Commits since {previous_tag or 'the first release'}\n\n```\n{commits}\n```" if commits else "",
        notes,
        "## Notes\n\n"
        "- No Minecraft client was available in the build environment: the jar compiles, packages and "
        "resolves its mixins against 26.3, but module behaviour is not observed here.\n"
        "- Module keybinds are set in the click GUI (Bind row in a module's settings); right-click "
        "clears a bind. Settings persist in `config/veil-client.json`.",
        "## Verifying the download\n\n"
        f"```\nSHA-256 {sha}\n{jar_name}  ({size:,} bytes)\n```\n\n"
        f"On Windows: `certutil -hashfile {jar_name} SHA256`\n"
        f"On Linux/macOS: `sha256sum {jar_name}`",
    ] if part)

    if args.dry_run:
        # Leave the tree exactly as it was: a dry run is meant to prove the build, not change it.
        with open(props_path, "w", encoding="utf-8", newline="") as props:
            props.write(original)
        print(f"dry run: {jar_name} built and verified; version left at {current}")
        print()
        print(body)
        sys.exit(0)

    # 6 ── commit, push, tag
    token = github_token(repo_dir)
    authed = f"https://deepseek-acg:{token}@github.com/{REPO_SLUG}.git"
    plain = f"https://github.com/{REPO_SLUG}.git"
    git("add", "gradle.properties", cwd=repo_dir)
    git(*AUTHOR, "commit", "-m", f"chore: bump version to {version}", cwd=repo_dir)
    git("remote", "set-url", "origin", authed, cwd=repo_dir)
    try:
        git("push", "origin", "main", cwd=repo_dir)
        git("tag", tag, cwd=repo_dir)
        git("push", "origin", f"refs/tags/{tag}", cwd=repo_dir)
    finally:
        git("remote", "set-url", "origin", plain, cwd=repo_dir)
    print(f"pushed {tag}")

    # 7 ── release and asset
    status, release = api(token, "POST", f"https://api.github.com/repos/{REPO_SLUG}/releases", {
        "tag_name": tag,
        "target_commitish": git("rev-parse", "HEAD", cwd=repo_dir),
        "name": f"Veil {version} — Minecraft 26.3",
        "body": body,
        "draft": False,
        "prerelease": False,
        "make_latest": "true",
    })
    if status >= 300:
        sys.exit(f"release creation failed: {status} {release.get('message')}")

    status, asset = api(token, "POST", f"{release['upload_url'].split('{')[0]}?name={jar_name}",
                        raw=data, content_type="application/java-archive")
    if status >= 300:
        sys.exit(f"asset upload failed: {status} {asset.get('message')}")

    status, latest = api(token, "GET", f"https://api.github.com/repos/{REPO_SLUG}/releases/latest")
    assets = [(item["name"], item["size"], item.get("digest")) for item in latest.get("assets", [])]
    expected = [(jar_name, size, f"sha256:{sha}")]
    if assets != expected:
        sys.exit(f"asset does not match what was built: {assets} != {expected}")
    print(f"released {tag}: {jar_name}, {size} bytes, sha256 {sha}")


if __name__ == "__main__":
    main()
