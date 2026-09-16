#!/usr/bin/env python3
"""Read-only checks for F-Droid release metadata; never build or create a tag."""

import argparse
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
GRADLE_PATH = "app/build.gradle"
VERSION_NAME = r"^\s*versionName\s+[\"']([0-9]+\.[0-9]+\.[0-9]+)[\"']\s*$"
VERSION_CODE = r"^\s*versionCode\s+([0-9]+)\s*$"


def fail(message):
    raise SystemExit(f"F-Droid release check failed: {message}")


def git(*args, allow_missing=False):
    result = subprocess.run(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8", capture_output=True
    )
    if result.returncode:
        if allow_missing:
            return None
        fail(result.stderr.strip())
    return result.stdout.strip()


def literal(pattern, source, label):
    matches = re.findall(pattern, source, re.MULTILINE)
    if len(matches) != 1:
        fail(f"{label} must appear exactly once as a static literal in {GRADLE_PATH}.")
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="Validate this proposed tag without creating it.")
    args = parser.parse_args()
    source = (ROOT / GRADLE_PATH).read_text(encoding="utf-8")
    version = literal(VERSION_NAME, source, "versionName")
    code = int(literal(VERSION_CODE, source, "versionCode"))
    app_id = literal(r"^\s*applicationId\s+[\"']([^\"']+)[\"']\s*$", source, "applicationId")
    if app_id != "takagi.ru.monica.fdroid" or not 0 < code <= 2100000000:
        fail("Expected the F-Droid application ID and a valid positive Android versionCode.")

    expected_tag = f"v{version}"
    requested_tag = args.tag or os.environ.get("EXPECTED_TAG")
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        actual_tag = os.environ["GITHUB_REF_NAME"]
        if requested_tag and requested_tag != actual_tag:
            fail("The requested tag differs from the tag that triggered this workflow.")
        requested_tag = actual_tag
    if requested_tag and requested_tag != expected_tag:
        fail(f"Use {expected_tag}; the source declares versionName {version}.")

    for field, value in {
        "BASE_VERSION_NAME": version,
        "FULL_VERSION_NAME": version,
        "BUILD_DETAIL_TAG": str(code),
    }.items():
        pattern = rf"^\s*buildConfigField\s+'String',\s*'{field}',\s*'\"([^\"]+)\"'\s*$"
        if literal(pattern, source, field) != value:
            fail(f"{field} must match {value}.")
    filename_version = literal(
        r"^\s*def versionNameTag = sanitizeTag\('([^']+)', '0\.0\.0'\)\s*$",
        source, "APK filename version",
    )
    if filename_version != version:
        fail("The APK filename version must match versionName.")

    notes_path = ROOT / "Monica F-Droid发行说明.md"
    if not notes_path.is_file() or not notes_path.read_text(encoding="utf-8").startswith(
        f"# Monica for Android (F-Droid) {version}\n"
    ):
        fail("Update the root F-Droid release notes to match versionName.")

    changelog_root = ROOT / "fastlane/metadata/android"
    changelogs = list(changelog_root.glob(f"*/changelogs/{code}.txt"))
    if changelog_root / f"en-US/changelogs/{code}.txt" not in changelogs:
        fail(f"Add the English F-Droid summary to en-US/changelogs/{code}.txt.")
    for path in changelogs:
        body = path.read_text(encoding="utf-8")
        if not body.strip() or len(body) > 500:
            fail(f"{path.relative_to(ROOT)} must contain 1–500 characters.")

    if git("rev-parse", "--is-shallow-repository") != "false":
        fail("Fetch full history and tags before checking release version ordering.")
    current_commit = git("rev-parse", "HEAD")
    for tag in git("tag", "--list").splitlines():
        if tag == expected_tag:
            if git("rev-parse", f"refs/tags/{tag}^{{commit}}") != current_commit:
                fail(f"{tag} already identifies another commit; prepare a new version instead.")
            continue
        tagged_source = git("show", f"refs/tags/{tag}:{GRADLE_PATH}", allow_missing=True)
        if not tagged_source:
            continue
        tagged_codes = re.findall(VERSION_CODE, tagged_source, re.MULTILINE)
        if tagged_codes and code <= max(map(int, tagged_codes)):
            fail(f"versionCode {code} must exceed the code in the existing tag {tag}.")

    summary = (
        f"Ready: `{expected_tag}` / `{app_id}`, versionCode `{code}`.\n\n"
        "Static version fields, existing tags and F-Droid changelog files checked. "
        "No APK build, tag creation or Release publication was performed. "
        "F-Droid builds and publication are handled separately by F-Droid.\n"
    )
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)


if __name__ == "__main__":
    main()
