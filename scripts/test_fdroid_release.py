"""Exercise branch checks and strict release gates without changing real tags."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_check", Path(__file__).with_name("check-fdroid-release.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class ReleaseCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = (check.ROOT / check.GRADLE_PATH).read_text(encoding="utf-8")
        self.version = check.literal(check.VERSION_NAME, source, "version")
        self.code = check.literal(check.VERSION_CODE, source, "code")
        self.tag = "v" + self.version
        self.source = source
        (self.root / "app").mkdir()
        (self.root / check.GRADLE_PATH).write_text(source, encoding="utf-8")
        self.notes = self.root / "Monica F-Droid发行说明.md"
        self.heading = f"# Monica for Android (F-Droid) {self.version}\n"
        self.notes.write_text("# 未发布\n\nPending changes\n\n" + self.heading, encoding="utf-8")
        changelog = self.root / f"fastlane/metadata/android/en-US/changelogs/{self.code}.txt"
        changelog.parent.mkdir(parents=True)
        changelog.write_text("Published changes", encoding="utf-8")
        self.tags = [self.tag]

    def git(self, *args, **kwargs):
        if args == ("rev-parse", "--is-shallow-repository"):
            return "false"
        if args == ("rev-parse", "HEAD"):
            return "development-commit"
        if args == ("tag", "--list"):
            return "\n".join(self.tags)
        if args[0] == "rev-parse":
            return "published-commit"
        if args[0] == "show":
            return self.source
        raise AssertionError(args)

    def run_check(self, args=(), env=None):
        output = io.StringIO()
        with patch.object(check, "ROOT", self.root), patch.object(check, "git", self.git), \
             patch("sys.argv", ["check"] + list(args)), patch.dict(os.environ, env or {}, clear=True), \
             contextlib.redirect_stdout(output):
            check.main()
        return output.getvalue()

    def test_branch_with_empty_workflow_input_accepts_unreleased_notes(self):
        self.assertIn("already published", self.run_check(env={"EXPECTED_TAG": ""}))

    def test_proposed_tag_rejects_unreleased_notes(self):
        with self.assertRaisesRegex(SystemExit, "Finalize the unreleased"):
            self.run_check(["--tag", self.tag])

    def test_tag_event_still_rejects_reusing_a_published_tag(self):
        self.notes.write_text(self.heading, encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "already identifies another commit"):
            self.run_check(env={"EXPECTED_TAG": "", "GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": self.tag})

    def test_fresh_proposed_tag_passes_with_final_notes(self):
        self.tags = []
        self.notes.write_text(self.heading, encoding="utf-8")
        self.assertIn("Metadata checked", self.run_check(["--tag", self.tag]))

    def test_branch_still_rejects_mismatched_version_heading(self):
        self.notes.write_text("# A different version\n", encoding="utf-8")
        with self.assertRaisesRegex(SystemExit, "match versionName"):
            self.run_check()

    def test_branch_still_rejects_nonincreasing_version_code(self):
        self.tags = ["v0.0.1"]
        with self.assertRaisesRegex(SystemExit, "must exceed"):
            self.run_check()


if __name__ == "__main__":
    unittest.main()
