import html
import json
import shutil
import sys
from pathlib import Path

remote_repo = Path.cwd()
local_repo = remote_repo.parent / "main" / "repo"
to_delete: list[str] = json.loads(sys.argv[1])

for module in to_delete:
    for apk in (remote_repo / "apk").glob(f"tachiyomi-{module}-v*.*.*.apk"):
        print(apk.name)
        apk.unlink(missing_ok=True)
    icon = remote_repo / "icon" / f"eu.kanade.tachiyomi.extension.{module}.png"
    if icon.exists():
        print(icon.name)
        icon.unlink()

for directory in ("apk", "icon"):
    source = local_repo / directory
    if source.exists():
        shutil.copytree(source, remote_repo / directory, dirs_exist_ok=True)

with (remote_repo / "index.json").open(encoding="utf-8") as index_file:
    remote_index = json.load(index_file)

local_index_path = local_repo / "index.min.json"
if local_index_path.exists():
    with local_index_path.open(encoding="utf-8") as index_file:
        local_index = json.load(index_file)
else:
    local_index = []

index = [
    item for item in remote_index
    if not any(item["pkg"].endswith(f".{module}") for module in to_delete)
]
index.extend(local_index)
index.sort(key=lambda item: item["pkg"])

with (remote_repo / "index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

for item in index:
    for source in item["sources"]:
        source.pop("versionId", None)

with (remote_repo / "index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, separators=(",", ":"))

with (remote_repo / "index.html").open("w", encoding="utf-8") as index_file:
    index_file.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n'
        "<title>apks</title>\n</head>\n<body>\n<pre>\n",
    )
    for item in index:
        apk_url = "apk/" + html.escape(item["apk"])
        name = html.escape(item["name"])
        index_file.write(f'<a href="{apk_url}">{name}</a>\n')
    index_file.write("</pre>\n</body>\n</html>\n")
