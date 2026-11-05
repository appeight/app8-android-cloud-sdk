#!/usr/bin/env bash
# Version consistency for the App8 release train (see README "Versioning").
#
#   scripts/check-version.sh                 # CI on every PR / push
#   scripts/check-version.sh --release <tag> # release workflow and /release preflight
#
# Always:
#   - SDKVersion.CURRENT is SemVer (MAJOR.MINOR.PATCH, optional -prerelease). Gradle reads it for
#     the Maven version AND the exact `dev.app8:engine` pin, so the two cannot drift.
#   - With a sibling ../app8-android-sdk checkout (the composite build), its
#     EngineVersion.CURRENT equals SDKVersion.CURRENT.
# With --release <tag>:
#   - <tag> (a leading "v" is ignored) equals SDKVersion.CURRENT.
#   - app8-android-sdk has the tag <version>: the engine this SDK pins is released.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION_FILE="cloud/src/main/kotlin/dev/app8/cloud/internal/SDKVersion.kt"
ENGINE_FILE="../app8-android-sdk/engine-core/src/main/kotlin/dev/app8/engine/core/EngineVersion.kt"
SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$'
ENGINE_REPO=https://github.com/appeight/app8-android-sdk.git

fail() { echo "::error::$*" >&2; exit 1; }

sdk=$(sed -nE 's/.*CURRENT: *String *= *"([^"]+)".*/\1/p' "$VERSION_FILE")
[[ -n "$sdk" ]] || fail "SDKVersion.CURRENT not found in $VERSION_FILE"
[[ "$sdk" =~ $SEMVER ]] || fail "SDKVersion.CURRENT '$sdk' is not SemVer"
echo "SDKVersion.CURRENT = $sdk (pins dev.app8:engine:$sdk)"

if [[ -f "$ENGINE_FILE" ]]; then
    engine=$(sed -nE 's/.*CURRENT: *String *= *"([^"]+)".*/\1/p' "$ENGINE_FILE")
    [[ "$engine" == "$sdk" ]] ||
        fail "../app8-android-sdk EngineVersion.CURRENT ($engine) != SDKVersion.CURRENT ($sdk); check out the matching engine tag"
    echo "../app8-android-sdk EngineVersion.CURRENT = $engine"
fi

if [[ "${1:-}" == "--release" ]]; then
    tag=${2:?usage: check-version.sh --release <tag>}
    [[ "${tag#v}" == "$sdk" ]] || fail "tag '$tag' != SDKVersion.CURRENT ($sdk)"
    git ls-remote --exit-code --tags "$ENGINE_REPO" "refs/tags/$sdk" > /dev/null ||
        fail "app8-android-sdk has no tag '$sdk'; release the engine first"
    echo "Release $tag: app8-android-sdk tag $sdk exists"
fi
