package dev.app8.cloud.internal.data

import dev.app8.cloud.internal.net.FlowManifestResponse
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.parseJson
import dev.app8.engine.App8DataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Scopes an engine to one published flow (iOS `FlowScopedDataSource`):
 * - [getApp] synthesizes a one-flow manifest (`navigation.startFlow` = the flow key), so the
 *   engine's flow machinery runs unchanged;
 * - [getScreen] goes to the flow-scoped endpoint, keeping members off the single-screen channel;
 * - styles and components are the flow's pinned set; assets, datasources and translations are
 *   app-level.
 */
internal class FlowScopedDataSource(
    private val parent: RenderingDataSource,
    private val flowKey: String,
    private val version: String?,
    manifest: FlowManifestResponse,
) : App8DataSource {

    private val startScreen = manifest.startScreen
    private val screenKeys = manifest.screens.map { it.screenKey }

    /** The flow's own pinned transitions, when the backend ships them; else the app-level registry. */
    private val flowTransitions = manifest.transitions.orEmpty()

    override suspend fun getApp(): ByteArray {
        val m = linkedMapOf<String, JsonElement>(
            "title" to JsonPrimitive(flowKey),
            "defaultUserInterfaceStyle" to JsonPrimitive("light"),
            "navigation" to JsonObject(
                mapOf(
                    "startFlow" to JsonPrimitive(flowKey),
                    "flows" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(flowKey), "startScreen" to JsonPrimitive(startScreen))))),
                ),
            ),
        )
        // Screens that name a transition by id animate with it instead of the default.
        val transitions = resolveTransitions()
        if (transitions.isNotEmpty()) m["transitions"] = JsonArray(transitions)
        return JsonObject(m).toString().encodeToByteArray()
    }

    private suspend fun resolveTransitions(): List<JsonObject> {
        val source = flowTransitions.ifEmpty { parent.appManifestTransitions() }
        return source.mapNotNull { runCatching { parseJson(it).objOrNull }.getOrNull() }
    }

    override suspend fun getScreen(screenId: String): ByteArray = parent.getFlowScreenData(flowKey, screenId, version)

    override suspend fun getAllScreenIds(): List<String>? = screenKeys.ifEmpty { null }

    /** The flow's pinned style versions (app-level latest when none are pinned); the engine reads them once. */
    override suspend fun getStyles(): List<ByteArray> = parent.getFlowStyles(flowKey, version)

    override suspend fun getComponents(): List<ByteArray> = parent.getFlowComponents(flowKey, version)

    override suspend fun getComponent(componentId: String): ByteArray = parent.getComponent(componentId)

    override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = parent.getAsset(assetId, assetName)

    /** Member screens' inline datasources land in the app-level cache at fetch time. */
    override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray =
        parent.getDatasource(screenId, datasourceId)

    /**
     * Localizations stay app-level for v1: they are app-wide and the localization channel serves
     * latest, so per-flow pinning is deferred.
     */
    override suspend fun getTranslations(): ByteArray = parent.getTranslations()
}
