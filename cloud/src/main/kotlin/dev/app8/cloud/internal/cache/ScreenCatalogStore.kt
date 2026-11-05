package dev.app8.cloud.internal.cache

import dev.app8.cloud.ScreenCatalog
import dev.app8.cloud.internal.CloudJson
import dev.app8.cloud.internal.Diagnostics
import dev.app8.cloud.internal.SDKVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * In-memory + on-disk store of the published screen catalog (`GET /apps/{id}/screens`), consulted
 * on every `screen(id)` to short-circuit unknown ids without a round-trip (iOS
 * `ScreenCatalogStore`). Thread-safe; backed by one `screens_catalog.json`.
 */
internal class ScreenCatalogStore(
    private val appId: String,
    private val diskCache: DiskCache?,
    catalogTtl: Duration,
    private val log: Diagnostics,
    private val clock: () -> Instant = Instant::now,
) {
    /** Where the current snapshot came from; surfaced to telemetry. */
    @Serializable
    enum class Source(val wire: String) {
        /** `/apps/{id}/screens`. */
        @SerialName("network") NETWORK("network"),

        /** Loaded from disk at init. */
        @SerialName("disk") DISK("disk"),

        /** Only ids seen in successful renders (backend without the listing endpoint). */
        @SerialName("seededFromRender") SEEDED_FROM_RENDER("seededFromRender"),
    }

    /** `version` / `minDslVersion` are null for entries added by [mergeSeen]. */
    @Serializable
    data class Entry(val version: String? = null, val minDslVersion: String? = null, val updatedAt: String? = null)

    @Serializable
    private class PersistedSnapshot(
        val schemaVersion: String,
        val sdkVersion: String,
        val appId: String,
        @Serializable(with = InstantIsoSerializer::class) val fetchedAt: Instant,
        val source: Source,
        val backendSupportsEnumeration: Boolean,
        val screens: Map<String, Entry>,
    )

    private val lock = Any()
    private var screens: Map<String, Entry> = emptyMap()
    private var fetchedAt: Instant? = null
    private var source: Source = Source.SEEDED_FROM_RENDER
    private var backendSupportsEnumeration = false
    private var loaded = false
    private val ttl: Duration = if (catalogTtl.isNegative) Duration.ZERO else catalogTtl

    /** Hydrates synchronously from disk when a valid snapshot is present (one small JSON read). */
    fun loadFromDisk() {
        val cache = diskCache ?: return
        val data = cache.readScreensCatalog() ?: return
        val decoded = runCatching { CloudJson.decodeFromString(PersistedSnapshot.serializer(), data.decodeToString()) }.getOrNull()
        if (decoded == null) {
            log.warning { "ScreenCatalogStore: failed to decode ${CacheLayout.SCHEMA_VERSION} catalog — ignoring." }
            return
        }
        if (decoded.schemaVersion != CacheLayout.SCHEMA_VERSION) {
            log.info { "ScreenCatalogStore: disk catalog schema '${decoded.schemaVersion}' != current '${CacheLayout.SCHEMA_VERSION}' — discarding." }
            cache.clearScreensCatalog()
            return
        }
        if (decoded.appId != appId) {
            log.warning { "ScreenCatalogStore: disk catalog appId '${decoded.appId}' != current '$appId' — discarding." }
            cache.clearScreensCatalog()
            return
        }
        synchronized(lock) {
            screens = decoded.screens
            fetchedAt = decoded.fetchedAt
            // Tagged `disk` so telemetry tells a persisted catalog from a freshly served one.
            source = Source.DISK
            backendSupportsEnumeration = decoded.backendSupportsEnumeration
            loaded = true
        }
        log.debug { "ScreenCatalogStore: hydrated ${decoded.screens.size} screen(s) from disk (fetchedAt=${decoded.fetchedAt}, backendSupportsEnumeration=${decoded.backendSupportsEnumeration})." }
    }

    /** Replaces the catalog with a network snapshot and persists it. */
    fun replace(snapshot: NetworkSnapshot) {
        val now = clock()
        val dict = snapshot.screens.associate { it.screenKey to Entry(it.version, it.minDslVersion, it.updatedAt) }
        synchronized(lock) {
            screens = dict
            fetchedAt = now
            source = Source.NETWORK
            backendSupportsEnumeration = true
            loaded = true
        }
        persist(Source.NETWORK, true, now)
        log.info { "ScreenCatalogStore: refreshed from network — ${dict.size} screen(s)." }
    }

    /**
     * The backend has no listing endpoint (404). Keeps seeded entries and marks the catalog
     * loaded-but-not-enumerable, so unseen ids answer `catalogNotLoaded`.
     */
    fun markBackendUnsupported() {
        val now = clock()
        synchronized(lock) {
            fetchedAt = now
            source = Source.SEEDED_FROM_RENDER
            backendSupportsEnumeration = false
            loaded = true
        }
        persist(Source.SEEDED_FROM_RENDER, false, now)
        log.info { "ScreenCatalogStore: backend has no /screens endpoint — running in seed-from-render mode." }
    }

    /** Adds an id after a successful render. Idempotent; persists only on change. */
    fun mergeSeen(id: String) {
        val snap = synchronized(lock) {
            if (screens.containsKey(id)) return
            screens = screens + (id to Entry())
            // fetchedAt is the catalog's freshness clock, not the seeding moment: keep it.
            if (fetchedAt == null) {
                fetchedAt = clock()
                source = Source.SEEDED_FROM_RENDER
                loaded = true
            }
            Triple(source, backendSupportsEnumeration, fetchedAt!!)
        }
        persist(snap.first, snap.second, snap.third)
    }

    /** Drops an entry the screen endpoint 404'd although the catalog listed it. Persists immediately. */
    fun markStaleIfBackendSays404(id: String) {
        val snap = synchronized(lock) {
            if (!screens.containsKey(id)) return
            screens = screens - id
            fetchedAt?.let { Triple(source, backendSupportsEnumeration, it) }
        }
        snap?.let { persist(it.first, it.second, it.third) }
        log.info { "ScreenCatalogStore: dropped stale entry '$id' after backend 404." }
    }

    /** Drops in-memory state after `clearCache(All)` already wiped the file. */
    fun resetForCacheClear() {
        synchronized(lock) {
            screens = emptyMap()
            fetchedAt = null
            source = Source.SEEDED_FROM_RENDER
            backendSupportsEnumeration = false
            loaded = false
        }
    }

    fun publicSnapshot(): ScreenCatalog? = synchronized(lock) {
        val at = fetchedAt
        if (!loaded || at == null) null else ScreenCatalog(screens.keys.sorted(), at, backendSupportsEnumeration)
    }

    sealed interface Decision {
        data class Known(val entry: Entry, val fetchedAt: Instant, val source: Source) : Decision
        data object UnknownFresh : Decision
        data object UnknownStale : Decision
        data object CatalogNotLoaded : Decision
    }

    /**
     * Tri-state lookup for `screen(id)` and `availability`. The TTL gates negatives only: a stale
     * `Known` may be wrong but the render path corrects it after a 404, while a stale `Unknown`
     * would hide a CTA forever. Without a network catalog, absence means nothing.
     */
    fun availability(id: String, now: Instant = clock()): Decision = synchronized(lock) {
        val at = fetchedAt
        if (!loaded || at == null) return Decision.CatalogNotLoaded
        val entry = screens[id]
        val fresh = !ttl.isZero && Duration.between(at, now) <= ttl
        when {
            entry != null -> Decision.Known(entry, at, source)
            backendSupportsEnumeration && fresh -> Decision.UnknownFresh
            backendSupportsEnumeration -> Decision.UnknownStale
            else -> Decision.CatalogNotLoaded
        }
    }

    val isLoaded: Boolean get() = synchronized(lock) { loaded }

    /** Catalog age in milliseconds; null before any load. */
    fun ageMillis(now: Instant = clock()): Long? = synchronized(lock) { fetchedAt?.let { Duration.between(it, now).toMillis() } }

    fun currentSource(): Source = synchronized(lock) { source }

    fun screenCount(): Int = synchronized(lock) { screens.size }

    private fun persist(source: Source, enumerable: Boolean, fetchedAt: Instant) {
        val cache = diskCache ?: return
        val current = synchronized(lock) { screens }
        val payload = PersistedSnapshot(CacheLayout.SCHEMA_VERSION, SDKVersion.CURRENT, appId, fetchedAt, source, enumerable, current)
        val bytes = runCatching { CloudJson.encodeToString(PersistedSnapshot.serializer(), payload).encodeToByteArray() }.getOrNull()
        if (bytes == null) {
            log.warning { "ScreenCatalogStore: failed to encode catalog snapshot — skipping persist." }
            return
        }
        cache.writeScreensCatalog(bytes)
    }

    /** The `/apps/{id}/screens` shape the data source hands over. */
    class NetworkSnapshot(val screens: List<Screen>) {
        class Screen(val screenKey: String, val version: String, val minDslVersion: String, val updatedAt: String?)
    }
}
