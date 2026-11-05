pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The engine (`dev.app8:engine`) comes from a sibling `app8-android-sdk` checkout when there is
// one (composite build: engine changes are picked up without publishing), otherwise from GitHub
// Packages at the exact version this SDK pins. `-Papp8.engine.local=false` forces the published
// artifact. `scripts/fetch-engine.sh` clones the sibling at the matching tag.
val engineCheckout = file("../app8-android-sdk")
val useLocalEngine = providers.gradleProperty("app8.engine.local").orNull != "false"
if (useLocalEngine && engineCheckout.resolve("settings.gradle.kts").isFile) {
    includeBuild(engineCheckout)
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven {
            name = "App8Engine"
            url = uri("https://maven.pkg.github.com/appeight/app8-android-sdk")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
            content { includeGroup("dev.app8") }
        }
    }
}
rootProject.name = "app8-android-cloud-sdk"
include(":cloud", ":sample")
