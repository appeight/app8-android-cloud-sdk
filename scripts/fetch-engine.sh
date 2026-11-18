#!/usr/bin/env bash
# Clones app8-android-sdk next to this repository for the composite build
# (settings.gradle.kts includes ../app8-android-sdk when it exists).
#
#   scripts/fetch-engine.sh [--strict] [<dir>]   # default dir: ../app8-android-sdk
#
# Checks out the tag equal to SDKVersion.CURRENT. Without --strict, while the engine release is
# not tagged yet, it falls back to the engine's own version branch (version-<version>, the
# engine's "Version <version>" pull request) and then to main. So this repo's version pull
# request is tested against the engine it will ship with, also before that engine pull request
# is merged. The version check still compares EngineVersion.CURRENT in every case.
# An existing checkout is left untouched.
set -euo pipefail

cd "$(dirname "$0")/.."
strict=false
if [[ "${1:-}" == "--strict" ]]; then strict=true; shift; fi
dir=${1:-../app8-android-sdk}
repo=https://github.com/appeight/app8-android-sdk.git
version=$(sed -nE 's/.*CURRENT: *String *= *"([^"]+)".*/\1/p' cloud/src/main/kotlin/dev/app8/cloud/internal/SDKVersion.kt)

if [[ -d "$dir/.git" ]]; then
    echo "$dir exists; leaving it as is"
    exit 0
fi
if git ls-remote --exit-code --tags "$repo" "refs/tags/$version" > /dev/null; then
    git clone --depth 1 --branch "$version" "$repo" "$dir"
elif [[ "$strict" == true ]]; then
    echo "::error::app8-android-sdk has no tag '$version'" >&2
    exit 1
elif git ls-remote --exit-code --heads "$repo" "refs/heads/version-$version" > /dev/null; then
    echo "app8-android-sdk tag '$version' not found; using branch version-$version"
    git clone --depth 1 --branch "version-$version" "$repo" "$dir"
else
    echo "app8-android-sdk tag '$version' not found; using main"
    git clone --depth 1 "$repo" "$dir"
fi
