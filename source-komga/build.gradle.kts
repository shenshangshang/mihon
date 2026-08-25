plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.source.komga"
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.i18n)
    implementation(projects.domain)
    implementation(projects.core.common)

    implementation(libs.metro.runtime)
    implementation(libs.bundles.serialization)

    implementation(libs.okhttp.core)
}
