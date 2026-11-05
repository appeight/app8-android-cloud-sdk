// Sample host: renders the bundled offline `.a8pack` with no network.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.app8.cloud.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.app8.cloud.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The `.a8pack` assets are read as-is.
    androidResources { noCompress += listOf("json", "jpg", "png") }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":cloud"))
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}
