package dev.app8.cloud.internal.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** `/sdk/v1` endpoints (GET except [Telemetry]). iOS `Endpoint`. */
internal sealed class Endpoint {
    abstract val appId: String

    data class Manifest(override val appId: String) : Endpoint()
    data class Screen(override val appId: String, val screenId: String, val version: String?) : Endpoint()
    data class Styles(override val appId: String) : Endpoint()
    data class Components(override val appId: String) : Endpoint()
    data class AssetsManifest(override val appId: String) : Endpoint()
    data class ListScreens(override val appId: String) : Endpoint()
    data class Telemetry(override val appId: String) : Endpoint()

    /** Full all-locales bundle the engine loads into its translation store. */
    data class Localizations(override val appId: String) : Endpoint()

    /** Published flows (gated multi-screen bundles). */
    data class ListFlows(override val appId: String) : Endpoint()
    data class Flow(override val appId: String, val flowKey: String, val version: String?) : Endpoint()

    /** Flow-scoped member screen: not the same bytes channel as [Screen]. */
    data class FlowScreen(override val appId: String, val flowKey: String, val screenKey: String, val version: String?) : Endpoint()

    /** Flow-pinned styles / components (the flow's design-system version set). */
    data class FlowStyles(override val appId: String, val flowKey: String, val version: String?) : Endpoint()
    data class FlowComponents(override val appId: String, val flowKey: String, val version: String?) : Endpoint()

    val path: String
        get() = when (this) {
            is Manifest -> "/apps/$appId/manifest"
            is Screen -> "/apps/$appId/screens/$screenId"
            is Styles -> "/apps/$appId/styles"
            is Components -> "/apps/$appId/components"
            is AssetsManifest -> "/apps/$appId/assets/manifest"
            is ListScreens -> "/apps/$appId/screens"
            is Telemetry -> "/apps/$appId/telemetry"
            is Localizations -> "/apps/$appId/localizations"
            is ListFlows -> "/apps/$appId/flows"
            is Flow -> "/apps/$appId/flows/$flowKey"
            is FlowScreen -> "/apps/$appId/flows/$flowKey/screens/$screenKey"
            is FlowStyles -> "/apps/$appId/flows/$flowKey/styles"
            is FlowComponents -> "/apps/$appId/flows/$flowKey/components"
        }

    val method: String get() = if (this is Telemetry) "POST" else "GET"

    val queryItems: List<Pair<String, String>>
        get() {
            val version = when (this) {
                is Screen -> version
                is Flow -> version
                is FlowScreen -> version
                is FlowStyles -> version
                is FlowComponents -> version
                else -> null
            }
            return if (version != null) listOf("version" to version) else emptyList()
        }

    val coalesceKey: String
        get() = when (this) {
            is Manifest -> "manifest:$appId"
            is Screen -> "screen:$appId:$screenId:${version ?: LATEST}"
            is Styles -> "styles:$appId"
            is Components -> "components:$appId"
            is AssetsManifest -> "assets:$appId"
            is ListScreens -> "list-screens:$appId"
            is Telemetry -> "telemetry:$appId"
            is Localizations -> "localizations:$appId"
            is ListFlows -> "list-flows:$appId"
            is Flow -> "flow:$appId:$flowKey:${version ?: LATEST}"
            is FlowScreen -> "flow-screen:$appId:$flowKey:$screenKey:${version ?: LATEST}"
            is FlowStyles -> "flow-styles:$appId:$flowKey:${version ?: LATEST}"
            is FlowComponents -> "flow-components:$appId:$flowKey:${version ?: LATEST}"
        }

    /** [base] is the `/sdk/v1` base URL; path segments are percent-encoded. */
    fun resolve(base: String): HttpUrl {
        val builder = base.trimEnd('/').toHttpUrl().newBuilder()
        path.trimStart('/').split('/').forEach { builder.addPathSegment(it) }
        queryItems.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private companion object {
        const val LATEST = "_latest"
    }
}
