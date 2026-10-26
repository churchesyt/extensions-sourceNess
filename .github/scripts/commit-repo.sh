#!/bin/bash
set -e

git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git config user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push origin repo
    curl -fsS "https://purge.jsdelivr.net/gh/${GITHUB_REPOSITORY}@repo/index.min.json"
else
    echo "No changes to commit"
fi
