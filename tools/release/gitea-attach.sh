#!/usr/bin/env bash
# Attach one file to the Gitea pre-release for TAG, creating the release if it doesn't exist yet.
# Each release job (Windows mod, Linux app) attaches its own asset, so no artifact passing is needed.
# Usage: TOKEN=... gitea-attach.sh <repo API url> <tag> <web base for notes links> <file>
set -euo pipefail
API=$1 TAG=$2 WEB=$3 FILE=$4
auth=(-H "Authorization: token $TOKEN")
release_id() { curl -fsS "${auth[@]}" "$API/releases/tags/$TAG" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])'; }
if ! id=$(release_id 2>/dev/null); then
  notes=$(sed -e "s|{TAG}|$TAG|g" -e "s|{WEB}|$WEB|g" "$(dirname "$0")/notes.md")
  body=$(python3 -c 'import json,sys;print(json.dumps({"tag_name":sys.argv[1],"name":sys.argv[1],"body":sys.argv[2],"prerelease":True,"draft":False}))' "$TAG" "$notes")
  # The other job may create it at the same moment; a failed create is fine if the release now exists.
  curl -fsS "${auth[@]}" -H "Content-Type: application/json" -X POST "$API/releases" -d "$body" >/dev/null || true
  id=$(release_id)
fi
curl -fsS "${auth[@]}" -X POST -F "attachment=@$FILE" "$API/releases/$id/assets?name=$(basename "$FILE")" >/dev/null
echo "attached $(basename "$FILE") to release $TAG (id $id)"
