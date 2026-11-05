package dev.app8.cloud.internal.offline

import android.content.Context
import dev.app8.cloud.App8Cloud

/**
 * Every `.a8pack` in the two default locations (iOS `discoverOfflinePackages`): the APK's
 * `assets/App8OfflinePackages/` folder (ship-with-app) and
 * [App8Cloud.defaultOfflinePackageDirectory] (downloaded at runtime).
 */
internal object OfflinePackageDiscovery {
    const val ASSETS_FOLDER: String = "App8OfflinePackages"

    fun discover(context: Context, appId: String): List<PackageSource> {
        val found = ArrayList<PackageSource>()
        runCatching {
            val assets = context.assets
            assets?.list(ASSETS_FOLDER)?.filter { it.endsWith(".a8pack") }?.sorted()?.forEach {
                found += PackageSource.Assets(assets, "$ASSETS_FOLDER/$it")
            }
        }
        runCatching {
            App8Cloud.defaultOfflinePackageDirectory(context, appId).listFiles()
                ?.filter { it.isDirectory && it.extension == "a8pack" }
                ?.sortedBy { it.name }
                ?.forEach { found += PackageSource.Directory(it) }
        }
        return found
    }
}
