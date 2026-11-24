#!/usr/bin/env python3
"""Replace verified assets on an existing pre-release while preserving its tag."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile


def gh(*args):
    return subprocess.check_output(["gh", *args])


def verify_assets(assets, expected):
    for name, digest in expected.items():
        asset = assets.get(name)
        if not asset or asset["state"] != "uploaded" or asset.get("digest") != digest:
            raise RuntimeError(f"Release asset failed verification: {name}")


def refresh(repository, tag, directory, run):
    endpoint = f"repos/{repository}/releases"
    release = json.loads(gh("api", f"{endpoint}/tags/{tag}"))
    if not release["prerelease"] or release["draft"] or release.get("immutable", False):
        raise RuntimeError(f"{tag} must be a published, editable pre-release")

    def assets():
        current = json.loads(gh("api", f"{endpoint}/{release['id']}"))
        return {asset["name"]: asset for asset in current["assets"]}

    def rename(asset, name):
        gh("api", "-X", "PATCH", f"{endpoint}/assets/{asset['id']}", "-f", f"name={name}")

    def remove(asset):
        gh("api", "-X", "DELETE", f"{endpoint}/assets/{asset['id']}")

    def digest(path):
        with path.open("rb") as file:
            return f"sha256:{hashlib.file_digest(file, 'sha256').hexdigest()}"

    files = sorted(directory.iterdir(), key=lambda p: (p.name == "SHA256SUMS.txt", p.name))
    expected = {path.name: digest(path) for path in files}
    original = assets()
    if not expected.keys() <= original.keys():
        raise RuntimeError("The existing pre-release is missing assets targeted for replacement")

    staged_names = {name: f"staged-{run}-{name}" for name in expected}
    backup_names = {name: f"backup-{run}-{name}" for name in expected}
    if original.keys() & (set(staged_names.values()) | set(backup_names.values())):
        raise RuntimeError("Temporary assets already exist for this run")

    old_build = json.loads(gh("release", "download", tag, "-R", repository,
                              "-p", "BUILD-MANIFEST.json", "-O", "-"))
    new_build = json.loads((directory / "BUILD-MANIFEST.json").read_text())
    for field in ("version_name", "application_id", "signing_certificate_sha256"):
        if old_build[field] != new_build[field]:
            raise RuntimeError(f"Replacement APKs differ in {field}")
    if new_build["version_code"] <= old_build["version_code"]:
        raise RuntimeError("Replacement APKs must have a newer Android version code")

    try:
        with tempfile.TemporaryDirectory() as tmp:
            staged_files = []
            for path in files:
                staged = Path(tmp) / staged_names[path.name]
                shutil.copyfile(path, staged)
                staged_files.append(str(staged))
            gh("release", "upload", tag, "-R", repository, *staged_files)

        uploaded = assets()
        verify_assets(uploaded, {staged_names[name]: value for name, value in expected.items()})
        for name in expected:
            if uploaded[name]["id"] != original[name]["id"]:
                raise RuntimeError(f"The existing asset changed during upload: {name}")
        for name in expected:
            rename(original[name], backup_names[name])
            rename(uploaded[staged_names[name]], name)
        verify_assets(assets(), expected)
    except BaseException:
        # Read the current names again in case a rename succeeded before its request failed.
        current = assets()
        for name in reversed(expected):
            if backup_names[name] in current:
                if name in current:
                    rename(current[name], staged_names[name])
                rename(current[backup_names[name]], name)
        current = assets()
        for staged_name in staged_names.values():
            if staged_name in current:
                remove(current[staged_name])
        raise

    current = assets()
    for backup_name in backup_names.values():
        remove(current[backup_name])
    print(f"Verified and replaced {len(expected)} assets on {tag}")


def interrupted(_sig, _frame):
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    refresh(os.environ["GITHUB_REPOSITORY"], os.environ["RELEASE_TAG"],
            Path(sys.argv[1]), f"{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}")
