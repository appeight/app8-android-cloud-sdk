package dev.app8.cloud

/** Result of importing one `.a8pack` bundle into the disk cache. */
public data class OfflineImportSummary(
    /** `app8.offline.flow` or `app8.offline.screen`. */
    val type: String,
    /** The flow key or screen key the bundle carries. */
    val key: String,
    /** Published version label (`null` = latest). */
    val version: String?,
    /** Screen documents seeded (the screen, or the flow members). */
    val screensImported: Int,
    /** Asset binaries written to the asset cache. */
    val assetsImported: Int,
    /** Fonts registered with the font registry. */
    val fontsRegistered: Int,
)
