package dev.app8.cloud.internal.net

import dev.app8.cloud.internal.blobArrayOrNull
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.parseJson
import dev.app8.cloud.internal.string
import dev.app8.cloud.internal.toBytes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

private fun root(bytes: ByteArray): JsonObject =
    parseJson(bytes).objOrNull ?: throw IllegalArgumentException("expected a JSON object")

private fun JsonObject.required(key: String): JsonElement =
    this[key]?.takeIf { it !is JsonNull } ?: throw IllegalArgumentException("missing '$key'")

private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalArgumentException("missing string '$key'")

/** `GET /apps/{id}/screens/{id}?version=…` (and the flow-member variant). DSL payloads stay raw bytes. */
internal class ScreenRenderResponse(
    /** May differ from the request (nil → latest published). */
    val servedVersion: String?,
    val data: ByteArray,
    val styles: List<ByteArray>?,
    val components: List<ByteArray>?,
    val datasources: Map<String, ByteArray>?,
) {
    companion object {
        fun decode(bytes: ByteArray): ScreenRenderResponse {
            val o = root(bytes)
            val ds = o["datasources"]
            return ScreenRenderResponse(
                servedVersion = o.string("servedVersion"),
                data = o.required("data").toBytes(),
                styles = o["styles"].blobArrayOrNull(),
                components = o["components"].blobArrayOrNull(),
                datasources = when (ds) {
                    null, is JsonNull -> null
                    is JsonObject -> ds.mapValues { it.value.toBytes() }
                    else -> throw IllegalArgumentException("datasources must be an object")
                },
            )
        }
    }
}

/** `GET /apps/{id}/manifest`. */
internal class AppManifestResponse(val configuration: ByteArray) {
    companion object {
        fun decode(bytes: ByteArray): AppManifestResponse = AppManifestResponse(root(bytes).required("configuration").toBytes())
    }
}

/**
 * `GET /apps/{id}/flows/{flowKey}?version=…`: the lazy flow manifest. Member screen bytes come
 * from `/flows/{flowKey}/screens/{screenKey}`.
 */
internal class FlowManifestResponse(
    val servedVersion: String?,
    val startScreen: String,
    val minDslVersion: String?,
    val screens: List<ScreenRef>,
    /**
     * The transition registry pinned with the flow (raw JSON objects), injected into the synthetic
     * flow manifest. Null/empty until the backend ships it; the app-level registry is used then.
     */
    val transitions: List<ByteArray>?,
) {
    class ScreenRef(val screenKey: String, val updatedAt: String?)

    companion object {
        fun decode(bytes: ByteArray): FlowManifestResponse {
            val o = root(bytes)
            return FlowManifestResponse(
                servedVersion = o.string("servedVersion"),
                startScreen = o.requiredString("startScreen"),
                minDslVersion = o.string("minDslVersion"),
                screens = o.required("screens").jsonArray.map { e ->
                    val s = e.objOrNull ?: throw IllegalArgumentException("screen ref must be an object")
                    ScreenRef(s.requiredString("screenKey"), s.string("updatedAt"))
                },
                transitions = o["transitions"].blobArrayOrNull(),
            )
        }
    }
}

/** `{ "items": [ … ] }` for styles and components; a missing/null `items` is empty. */
internal object ItemsResponse {
    fun decode(bytes: ByteArray): List<ByteArray> = root(bytes)["items"].blobArrayOrNull() ?: emptyList()
}

/** `GET /apps/{id}/screens`: published screens plus app-resource freshness pointers. */
internal class ListScreensResponse(val screens: List<ScreenItem>, val resources: Resources?) {
    class ScreenItem(val screenKey: String, val version: String, val minDslVersion: String, val updatedAt: String?)
    class Resources(val manifest: String?, val styles: String?, val components: String?, val localizations: String?)

    companion object {
        fun decode(bytes: ByteArray): ListScreensResponse {
            val o = root(bytes)
            val screens = o.required("screens").jsonArray.map { e ->
                val s = e.objOrNull ?: throw IllegalArgumentException("screen must be an object")
                ScreenItem(s.requiredString("screenKey"), s.requiredString("version"), s.requiredString("minDslVersion"), s.string("updatedAt"))
            }
            val r = o["resources"]?.objOrNull
            fun fresh(key: String): String? {
                val entry = r?.get(key) ?: return null
                if (entry is JsonNull) return null
                return (entry.objOrNull ?: throw IllegalArgumentException("resources.$key must be an object")).requiredString("updatedAt")
            }
            val resources = r?.let { Resources(fresh("manifest"), fresh("styles"), fresh("components"), fresh("localizations")) }
            return ListScreensResponse(screens, resources)
        }
    }
}

/** `GET /apps/{id}/assets/manifest`: presigned download URLs valid for `expiresIn` seconds. */
internal class AssetsResponse(val assets: List<AssetItem>, val expiresIn: Int) {
    class AssetItem(val id: String, val filename: String, val mimeType: String?, val downloadUrl: String)

    companion object {
        fun decode(bytes: ByteArray): AssetsResponse {
            val o = root(bytes)
            val assets = (o.required("assets") as? JsonArray ?: throw IllegalArgumentException("assets must be an array")).map { e ->
                val a = e.objOrNull ?: throw IllegalArgumentException("asset must be an object")
                AssetItem(a.requiredString("id"), a.requiredString("filename"), a.string("mimeType"), a.requiredString("downloadUrl"))
            }
            val expires = (o.required("expiresIn") as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("expiresIn must be an integer")
            return AssetsResponse(assets, expires)
        }
    }
}
