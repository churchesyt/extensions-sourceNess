import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")

build_tools = next((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())
repo_dir = Path("repo")
repo_apk_dir = repo_dir / "apk"
repo_icon_dir = repo_dir / "icon"
repo_icon_dir.mkdir(parents=True, exist_ok=True)

index_min_data = []

for metadata_file in (repo_dir / "metadata").glob("*.json"):
    with metadata_file.open(encoding="utf-8") as f:
        metadata = json.load(f)

    package_name = metadata["packageName"]
    package_suffix = package_name.removeprefix("eu.kanade.tachiyomi.extension.")
    apk = repo_apk_dir / f"tachiyomi-{package_suffix}-v{metadata['versionName']}.apk"
    if not apk.is_file():
        raise FileNotFoundError(f"{package_name}: release APK is missing: {apk.name}")

    badging = subprocess.check_output(
        [
            build_tools / "aapt",
            "dump",
            "--include-meta-data",
            "badging",
            apk,
        ],
    ).decode()

    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)

    with ZipFile(apk) as zip_file, zip_file.open(application_icon) as icon, (
        repo_icon_dir / f"{package_name}.png"
    ).open("wb") as icon_file:
        icon_file.write(icon.read())

    language = LANGUAGE_REGEX.search(apk.name).group(1)
    sources = metadata["sources"]

    if len(sources) == 1:
        source_language = sources[0]["lang"]
        if (
            source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    common_data = {
        "name": f"Tachiyomi: {metadata['name']}",
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": metadata["versionCode"],
        "version": metadata["versionName"],
        "nsfw": int(metadata["contentWarning"] != 1),
    }
    index_min_data.append(
        {
            **common_data,
            "sources": [
                {
                    "name": source["name"],
                    "lang": source["lang"],
                    "id": str(source["id"]),
                    "baseUrl": source["baseUrl"],
                }
                for source in sources
            ],
        },
    )

with (repo_dir / "index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))
