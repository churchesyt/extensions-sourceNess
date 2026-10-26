import json
import shutil
from pathlib import Path

repo_apk_dir = Path("repo/apk")
repo_metadata_dir = Path("repo/metadata")

shutil.rmtree(repo_apk_dir, ignore_errors=True)
repo_apk_dir.mkdir(parents=True, exist_ok=True)
shutil.rmtree(repo_metadata_dir, ignore_errors=True)
repo_metadata_dir.mkdir(parents=True, exist_ok=True)

artifact_dir = Path.home() / "apk-artifacts"
apks = list(artifact_dir.glob("**/*.apk"))
if not apks:
    raise FileNotFoundError("No APKs were downloaded from the build artifacts")

for apk in apks:
    shutil.move(apk, repo_apk_dir / apk.name.replace("-release.apk", ".apk"))

source_infos = list(artifact_dir.glob("**/keiyoushi-source-info.json"))
if not source_infos:
    raise FileNotFoundError("No extension source metadata was downloaded")

for source_info in source_infos:
    with source_info.open(encoding="utf-8") as f:
        metadata = json.load(f)
    shutil.copyfile(
        source_info,
        repo_metadata_dir / f"{metadata['packageName']}.json",
    )
