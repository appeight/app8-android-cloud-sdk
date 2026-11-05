# Contributing to App8Cloud for Android

Thanks for your interest in improving App8Cloud. This guide covers how to
build, test, and submit changes.

## Prerequisites

- JDK 17 or newer (CI uses Temurin 21)
- Android SDK with platform 37 (set `sdk.dir` in `local.properties` or `ANDROID_HOME`)
- An `app8-android-sdk` checkout next to this repository: `scripts/fetch-engine.sh`
  clones the tag equal to `SDKVersion.CURRENT` into `../app8-android-sdk`. Without
  it, Gradle resolves `dev.app8:engine` from GitHub Packages (set `GITHUB_ACTOR`
  and `GITHUB_TOKEN`, or `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`).

## Building and testing

```bash
./gradlew test                  # JVM unit tests
./gradlew :cloud:lint           # Android lint
./gradlew apiCheck              # public API matches cloud/api/cloud.api
./gradlew :sample:installDebug  # the sample renders its bundled .a8pack offline
scripts/check-version.sh        # SDKVersion is SemVer and matches the sibling engine
```

CI runs all of these except the install on every pull request; they must pass
before a change merges.

### Public API

`explicitApi()` is on and the module has an API dump in `cloud/api/cloud.api`.
When you change the public API on purpose, run `./gradlew apiDump` and commit
the updated dump with the change. Removing or changing a public signature is a
breaking change.

## Parity with iOS

App8Cloud for Android is the twin of
[`app8-ios-cloud-sdk`](https://github.com/appeight/app8-ios-cloud-sdk).

- **Port faithfully.** Cache layout, endpoints, error mapping, telemetry and
  analytics wire values match iOS. Android-specific differences are documented
  where they live.
- **Ship together.** A change to caching, networking, telemetry or the offline
  bundle format lands in both cloud SDKs in the same release; link the iOS pull
  request in yours.
- **Tests mirror iOS.** Each iOS test file has a Kotlin counterpart; port new
  iOS tests along with the behavior.

## Conventions

- New behavior comes with tests (MockWebServer for HTTP).
- No DI framework in the library; constructor injection.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`).

## Submitting changes

1. Fork the repository and create a branch for your change.
2. Make your change with accompanying tests.
3. Run the commands above and confirm they pass.
4. Open a pull request describing the change and its motivation.

Maintainers cut releases with `/release` (`.claude/commands/release.md`): the
engine release (`app8-android-sdk`) comes first, then this SDK at the same version.

By submitting a contribution you agree it is licensed under the Apache License
2.0, the same license as the project (see [LICENSE](LICENSE)).
