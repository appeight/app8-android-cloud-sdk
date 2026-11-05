package dev.app8.cloud.internal

/**
 * The App8Cloud SDK version (iOS `SDKVersion.current`).
 *
 * Sent in `X-App8-SDK-Version`, recorded in disk-cache metadata and stamped on bus events as
 * `cloudVersion`. Gradle reads it for the Maven version and the exact `dev.app8:engine` pin, so
 * it is the single place the version is written. It equals `EngineVersion.CURRENT` (one release
 * train); `SdkVersionTest` and `scripts/check-version.sh` enforce that.
 */
internal object SDKVersion {
    const val CURRENT: String = "0.4.1"
}
