package dev.app8.cloud

import java.time.Instant

/**
 * Whether the backend has a screen, answered without a network call
 * ([App8CloudInstance.availability]). [UNKNOWN] is a confirmed negative from a fresh catalog;
 * [CATALOG_NOT_LOADED] means "don't know yet", so host UI never confuses the two.
 */
public enum class ScreenAvailability {
    /** The catalog lists the screen as published. */
    KNOWN,

    /** The catalog is loaded, fresh, and does not list the screen. */
    UNKNOWN,

    /**
     * No authoritative answer: no catalog yet, a stale one, or a backend without
     * `/screens`. Wait with [App8CloudInstance.awaitCatalogReady].
     */
    CATALOG_NOT_LOADED,
}

/** Snapshot of the published screen ids (`GET /apps/{appId}/screens`, plus ids seeded by successful renders). */
public data class ScreenCatalog(
    val screenIds: List<String>,
    /** When the snapshot was captured; compare with [DiskCacheConfig.catalogTtl]. */
    val fetchedAt: Instant,
    /**
     * `true` when the backend served `/apps/{id}/screens`; `false` when the catalog holds only ids
     * seen in successful renders (absence then means nothing).
     */
    val backendSupportsEnumeration: Boolean,
)
