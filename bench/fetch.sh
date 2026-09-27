#!/usr/bin/env bash
# Fetch the pinned benchmark repositories into bench/.work/repos (idempotent).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repos="$here/.work/repos"
mkdir -p "$repos"
fetch() {
  local name="$1" url="$2" sha="$3"
  if [ ! -d "$repos/$name/.git" ]; then
    git clone --quiet "$url" "$repos/$name"
  fi
  git -C "$repos/$name" fetch --quiet origin "$sha" 2>/dev/null || true
  git -C "$repos/$name" -c advice.detachedHead=false checkout --quiet "$sha"
  echo "$name @ $(git -C "$repos/$name" rev-parse --short HEAD)"
}
fetch spring-petclinic https://github.com/spring-projects/spring-petclinic.git a6efbed773f61a271c071461326940786998722e
