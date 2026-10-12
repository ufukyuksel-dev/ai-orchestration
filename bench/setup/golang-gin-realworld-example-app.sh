#!/usr/bin/env bash
# Prepare the Go holdout repository (bench/tasks/go-holdout.json) in bench/.work/repos (idempotent).
# Needs: git, a Go toolchain on PATH (tested with go1.27.2; the module says go 1.21, no toolchain directive) and a
# C compiler for cgo (github.com/mattn/go-sqlite3). Network is needed once, for the clone and `go mod download`;
# afterwards the build, vet and tests run offline from the module cache.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
name=golang-gin-realworld-example-app
url=https://github.com/gothinkster/golang-gin-realworld-example-app.git
sha=626c372d259472148d93303f74aa9b9a1cdcef24
repo="$here/.work/repos/$name"

command -v go >/dev/null || { echo "go is not on PATH (e.g. brew install go)" >&2; exit 1; }
mkdir -p "$here/.work/repos"
if [ ! -d "$repo/.git" ]; then
  git clone --quiet "$url" "$repo"
fi
if ! git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null; then
  git -C "$repo" fetch --quiet origin "$sha" || git -C "$repo" fetch --quiet origin
fi
git -C "$repo" -c advice.detachedHead=false checkout --quiet "$sha"
(cd "$repo" && CGO_ENABLED=1 go mod download && CGO_ENABLED=1 go build ./...)
echo "$name @ $(git -C "$repo" rev-parse --short HEAD) ($(go version | awk '{print $3}'))"
