package dev.app8.cloud.internal.data

import dev.app8.cloud.internal.arrOrNull
import dev.app8.cloud.internal.objOrNull
import dev.app8.cloud.internal.parseJson
import dev.app8.cloud.internal.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** An image the DSL names (iOS `App8.AssetReference`). */
internal data class AssetReference(val id: String?, val name: String?, val url: String?)

/** A downloadable font file, by id or name in the asset manifest. */
internal data class AssetHint(val id: String?, val name: String?)

/** A font a screen may draw with (iOS `App8.FontReference`). */
internal data class FontReference(val postScriptName: String, val asset: AssetHint?)

internal data class AssetReferenceSet(val images: List<AssetReference>, val fonts: List<FontReference>)

/**
 * Finds the assets a screen needs before it paints. iOS asks the engine
 * (`collectAssetReferences(screenId:)`); the Android engine has no such API, so this walks the
 * raw DSL JSON instead:
 *
 * - images: every `remoteAsset` / `remote` source object (image, icon, video, poster) in the
 *   screen and in the component templates it references by `templateId`, skipping templated
 *   (`{{…}}`) names, which only resolve at render time;
 * - fonts: every face the stylesheets declare (`family.faces[].postScriptName` + `asset`, or a
 *   face-less `family.asset` keyed by `displayName`) plus every `fontFamily` name. Fonts are
 *   app-wide, so the first render registers the app's fonts once.
 */
internal object AssetReferenceCollector {

    fun collect(screen: ByteArray, styles: List<ByteArray>, components: List<ByteArray>): AssetReferenceSet {
        val screenJson = runCatching { parseJson(screen) }.getOrNull() ?: return AssetReferenceSet(emptyList(), emptyList())
        val templatesById = components.mapNotNull { blob ->
            runCatching { parseJson(blob) }.getOrNull()?.objOrNull?.let { o -> o.string("id")?.let { it to o } }
        }.toMap()

        val images = LinkedHashSet<AssetReference>()
        val fontNames = LinkedHashSet<String>()
        val visitedTemplates = HashSet<String>()
        val queue = ArrayDeque<JsonElement>().apply { add(screenJson) }
        while (queue.isNotEmpty()) {
            walk(queue.removeFirst()) { o ->
                imageRef(o)?.let(images::add)
                o.string("fontFamily")?.let(fontNames::add)
                o.string("templateId")?.let { id ->
                    if (visitedTemplates.add(id)) templatesById[id]?.let(queue::add)
                }
            }
        }

        val faces = LinkedHashMap<String, AssetHint?>()
        for (blob in styles) {
            val json = runCatching { parseJson(blob) }.getOrNull() ?: continue
            walk(json) { o ->
                o.string("fontFamily")?.let(fontNames::add)
                val family = o["family"]?.objOrNull ?: return@walk
                val displayName = family.string("displayName")
                val faceList = family["faces"]?.arrOrNull.orEmpty().mapNotNull { it.objOrNull }
                for (face in faceList) {
                    val ps = face.string("postScriptName") ?: continue
                    if (ps !in faces) faces[ps] = hint(face["asset"]?.objOrNull)
                }
                if (faceList.isEmpty() && displayName != null) {
                    val asset = hint(family["asset"]?.objOrNull)
                    if (asset != null && displayName !in faces) faces[displayName] = asset
                }
            }
        }
        for (name in fontNames) {
            if (name != "system" && name !in faces) faces[name] = null
        }
        return AssetReferenceSet(images.toList(), faces.map { (ps, asset) -> FontReference(ps, asset) })
    }

    private fun imageRef(o: JsonObject): AssetReference? {
        val kind = o.string("type") ?: o.string("kind") ?: return null
        if (kind != "remoteAsset" && kind != "remote" && kind != "url") return null
        fun clean(key: String) = o.string(key)?.takeIf { it.isNotEmpty() && "{{" !in it }
        val id = clean("id").takeIf { kind != "url" }
        val name = clean("name").takeIf { kind != "url" }
        val url = clean("url")
        if (id == null && name == null && url == null) return null
        return AssetReference(id, name, url)
    }

    private fun hint(o: JsonObject?): AssetHint? {
        o ?: return null
        val id = o.string("id")
        val name = o.string("name")
        return if (id == null && name == null) null else AssetHint(id, name)
    }
}

/**
 * Screen ids reachable from the app manifest (iOS engine `discoverAllReachableScreenIds`, which
 * Android's engine lacks): breadth-first from every `navigation.flows[].startScreen`, following
 * `nextScreen`, `completeFlow` `destination` and tab bar `tabs[].screen` anywhere in each screen
 * (component templates included).
 */
internal object ScreenReferenceWalker {

    suspend fun discover(
        manifest: ByteArray,
        components: List<ByteArray>,
        loadScreen: suspend (String) -> ByteArray?,
    ): List<String> {
        val app = runCatching { parseJson(manifest) }.getOrNull()?.objOrNull ?: return emptyList()
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        app["navigation"]?.objOrNull?.get("flows")?.arrOrNull?.forEach { flow ->
            flow.objOrNull?.string("startScreen")?.let { if (visited.add(it)) queue.add(it) }
        }
        // Template references count from any screen that uses them.
        val templateRefs = components.flatMap { blob -> runCatching { references(parseJson(blob)) }.getOrDefault(emptyList()) }
        var templatesQueued = false
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val bytes = runCatching { loadScreen(id) }.getOrNull() ?: continue
            val json = runCatching { parseJson(bytes) }.getOrNull() ?: continue
            val refs = references(json).toMutableList()
            if (!templatesQueued && bytes.decodeToString().contains("\"templateId\"")) {
                refs += templateRefs
                templatesQueued = true
            }
            refs.forEach { if (visited.add(it)) queue.add(it) }
        }
        return visited.toList()
    }

    fun references(json: JsonElement): List<String> {
        val out = ArrayList<String>()
        walk(json) { o ->
            o.string("nextScreen")?.let(out::add)
            if (o.string("type") == "completeFlow") o.string("destination")?.let(out::add)
            o["tabs"]?.arrOrNull?.forEach { tab -> tab.objOrNull?.string("screen")?.let(out::add) }
        }
        return out.filter { it.isNotEmpty() && "{{" !in it }
    }
}

/** Visits every JSON object in [element], depth-first. */
internal fun walk(element: JsonElement, visit: (JsonObject) -> Unit) {
    val stack = ArrayDeque<JsonElement>().apply { add(element) }
    while (stack.isNotEmpty()) {
        when (val e = stack.removeLast()) {
            is JsonObject -> {
                visit(e)
                e.values.forEach(stack::add)
            }
            is JsonArray -> e.forEach(stack::add)
            else -> Unit
        }
    }
}
