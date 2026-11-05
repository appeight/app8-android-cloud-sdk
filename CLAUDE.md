# Claude Code guidelines for App8Cloud Android

The Android twin of `app8-ios-cloud-sdk`: server-driven delivery (`/sdk/v1`),
disk cache, offline `.a8pack` bundles and telemetry on top of the App8 engine
(`app8-android-sdk`, artifact `dev.app8:engine`). One version across the train.

## Build and test

```bash
scripts/fetch-engine.sh              # ../app8-android-sdk at the pinned tag (composite build)
./gradlew test                       # JVM unit tests (MockWebServer for HTTP)
./gradlew :cloud:lint apiCheck       # lint, public API dump (cloud/api/cloud.api)
./gradlew apiDump                    # after an intended public API change; commit the dump
./gradlew :sample:installDebug       # sample: renders its bundled .a8pack offline
scripts/build-sample-pack.py         # rebuild the sample .a8pack from the engine's facetheory demo
```

The engine comes from `../app8-android-sdk` when present (`includeBuild` in
`settings.gradle.kts`), otherwise from GitHub Packages at exactly
`SDKVersion.CURRENT`; `-Papp8.engine.local=false` forces the published one.
Engine changes needed by this SDK land in `app8-android-sdk` first.

CI (`.github/workflows/ci.yml`) runs the tests, lint, `apiCheck`,
`scripts/check-version.sh` and the sample build. Releases: `/release`
(`.claude/commands/release.md`). `SDKVersion.CURRENT`
(`cloud/src/main/kotlin/dev/app8/cloud/internal/SDKVersion.kt`) is the only
place the version is written: Gradle reads it for the Maven version and the
exact engine pin, and `SdkVersionTest` asserts it equals `EngineVersion.CURRENT`.

Emulators: AVDs `a8_pixel_9_api37*` and `a8_pixel_6_api31` (floor). Other
sessions may share a running emulator; check `topResumedActivity` before
trusting a screenshot, or boot your own with `-port` and `-read-only`.

## Layout

- `cloud/src/main/kotlin/dev/app8/cloud/`: public API (`App8Cloud`,
  `App8CloudInstance`, config, errors, events, `compose/`, `view/`).
- `internal/net`: endpoints, headers, OkHttp client (retry, error mapping).
- `internal/cache`: `DiskCache`, `MetaStore`, `AssetCache`, `ScreenCatalogStore`.
- `internal/data`: `RenderingDataSource` (the engine's data source),
  `FlowScopedDataSource`, coalescer, JSON asset/screen reference walkers.
- `internal/fonts`, `internal/offline`, `internal/telemetry`, `internal/instance`
  (`CloudInstance` wires everything).
- Tests mirror the iOS test files one to one (`*Tests.swift` → `*Test.kt`).

## Parity rules

- Port from Swift faithfully: same cache layout and schema, same endpoints,
  same telemetry `type`s and context keys, same analytics names and properties.
  When behavior must differ on Android, say why in a KDoc and in the README.
- Wire values are shared with iOS and the backend (`telemetryReasonString`,
  size classes, idioms, `PrefetchEvent` statuses). Never rename them on one side.
- `docs/OFFLINE_BUNDLE_FORMAT.md` is the same contract as the iOS copy.
- A behavior change ships in both cloud SDKs in the same release.

## Android rules

- minSdk 31, compileSdk 37. No DI framework: constructor injection.
- `explicitApi()` is on; internals stay `internal`.
- Suspend functions are main-safe; IO runs on `Dispatchers.IO`. Mutable state
  is guarded (`synchronized`), never assumed main-thread.
- Tests run on the JVM: `TestContext` stands in for a `Context`, and the
  engine is created with `enforcePlatformFloor = false`.
