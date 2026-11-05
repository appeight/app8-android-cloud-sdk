package dev.app8.cloud

/** Delivered to [App8CloudInstance.onScreenRendered] after a successful render. */
public data class RenderEvent(
    val screenId: String,
    /** What the server served; may differ from the requested version. */
    val servedVersion: String?,
    val durationMs: Int,
    val fromCache: Boolean,
    /** Locale used for `{"$i18n": …}` on this render. */
    val servedLocale: String,
)

/** Delivered to [App8CloudInstance.onPrefetchCompleted] after every prefetch: per-resource and per-screen freshness. */
public data class PrefetchEvent(
    val durationMs: Int,
    /** `"all"` or `"specific"`. */
    val scope: String,
    val manifest: AppResourceStatus,
    val styles: AppResourceStatus,
    val components: AppResourceStatus,
    val localizations: AppResourceStatus,
    /**
     * Set when the status is [AppResourceStatus.REFRESHED]: `"updated_at_changed"`,
     * `"content_changed"` or `"no_prior_cache"`.
     */
    val manifestReason: String? = null,
    val stylesReason: String? = null,
    val componentsReason: String? = null,
    val localizationsReason: String? = null,
    val screensCount: Int,
    /** Cached and fresh, or fetched with unchanged bytes. */
    val screensCached: Int,
    /** Bytes replaced. */
    val screensRefreshed: Int,
    /** Subset of [screensRefreshed] whose cache the freshness pre-check wiped first. */
    val screensInvalidated: Int,
    val screensFailed: Int,
    val cancelled: Boolean,
) {
    public enum class AppResourceStatus(public val wire: String) {
        /** The cheap pre-check passed: cached bytes still valid, no fetch. */
        CACHED("cached"),
        /** Fetched and the bytes matched the stored hash: disk untouched. */
        UNCHANGED("unchanged"),
        /** The cache was replaced with fresh bytes. */
        REFRESHED("refreshed"),
        /** The refresh threw; cache unchanged. */
        FAILED("failed"),
        /** Manifest only: `prefetchAll` refreshed it before discovery. */
        SKIPPED("skipped"),
        /** Never attempted (e.g. cancelled early). */
        NOT_RUN("notRun"),
    }
}
