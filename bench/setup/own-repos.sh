#!/usr/bin/env bash
# Prepare the v2 suites on the maintainer's own projects (wordfall, wordfall-server, taplearn): clone each from a
# local checkout at the pinned commit into bench/.work/repos/<name>, check the suite's baseline patch applies, and
# warm the Gradle caches so scored runs can use --offline. Idempotent. The originals are only read (git clone).
#
#   WORDFALL_SRC=<path> WORDFALL_SERVER_SRC=<path> TAPLEARN_SRC=<path> bench/setup/own-repos.sh [name ...]
#
# Needs JDK 21 (macOS: /usr/libexec/java_home -v 21), ANDROID_HOME for the two KMP apps, Docker for the
# wordfall-server Testcontainers tests. No secrets are involved: none of the three repositories tracks key stores,
# local.properties, google-services files or .env; taplearn gets placeholder local.properties values.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
private="${BENCH_PRIVATE:-$HOME/.config/super-computer-ai/private/ai-orch-bench}"  # private suite assets
export JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}"

prepare() {
  local name=$1 src=$2 sha=$3 patch=$4 warm=$5
  local repo="$here/.work/repos/$name"
  if [ -z "$src" ] && [ ! -d "$repo/.git" ]; then
    echo "$name: set the source checkout variable (see header)" >&2; return 1
  fi
  if [ ! -d "$repo/.git" ]; then
    mkdir -p "$(dirname "$repo")"
    git clone --quiet --no-local "$src" "$repo"
  fi
  if ! git -C "$repo" cat-file -e "$sha^{commit}" 2>/dev/null; then
    git -C "$repo" fetch --quiet origin
  fi
  git -C "$repo" -c advice.detachedHead=false checkout --quiet --force "$sha"
  git -C "$repo" clean -fdq
  if [ -n "$patch" ]; then
    git -C "$repo" apply --check "$private/$patch"
    git -C "$repo" apply "$private/$patch"
  fi
  [ "$name" = taplearn ] && cp "$private/setup/taplearn.local.properties" "$repo/local.properties"
  echo "$name @ $(git -C "$repo" rev-parse --short HEAD): warming $warm"
  (cd "$repo" && ./gradlew $warm --console=plain -q) || echo "$name: warm-up build reported failures (see above)" >&2
  git -C "$repo" checkout --quiet --force "$sha"  # leave the clone at the pinned commit; runs apply the patch
}

names=("$@")
sel() { [ ${#names[@]} -eq 0 ] && return 0; for n in "${names[@]}"; do [ "$n" = "$1" ] && return 0; done; return 1; }

sel wordfall && prepare wordfall "${WORDFALL_SRC:-}" 48758af583e37924b0f333a3ff18cc0f592e8fb9 \
  setup/wordfall-host-tests.patch ":shared:core:engine:testAndroidHostTest :shared:core:model:testAndroidHostTest :shared:core:engine-vurgun:testAndroidHostTest :shared:core:engine-tetris:testAndroidHostTest :shared:core:engine-hidden:testAndroidHostTest :shared:core:engine-anagram:jvmTest :shared:data:repository:testAndroidHostTest"
sel wordfall-server && prepare wordfall-server "${WORDFALL_SERVER_SRC:-}" f4f4a85044d5187a95aa4e41d0a66505516b3116 \
  "" "testClasses"
sel taplearn && prepare taplearn "${TAPLEARN_SRC:-}" 9e89fd9f784813d20a4ff903758c46754473b6b0 \
  setup/taplearn-host-tests.patch "testAndroidHostTest :backend:test"
