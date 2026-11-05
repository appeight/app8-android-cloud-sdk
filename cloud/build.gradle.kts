// App8Cloud: server-driven delivery (`/sdk/v1`), disk cache, offline bundles and telemetry on top
// of the App8 engine. The Android twin of app8-ios-cloud-sdk.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "dev.app8.cloud"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    // JVM unit tests: android.jar stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    explicitApi()
}

dependencies {
    // Exact pin, like iOS `exact: "x.y.z"`: App8Cloud x.y.z ships engine x.y.z. With a sibling
    // app8-android-sdk checkout the composite build substitutes the engine project.
    api("dev.app8:engine:${project.version}")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "cloud"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("App8Cloud")
                description.set("Server-driven App8 UI over /sdk/v1: caching, offline bundles and telemetry on the App8 engine.")
            }
        }
    }
}
