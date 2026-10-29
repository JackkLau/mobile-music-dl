plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
    // Compile the app's network implementation directly. There is no second HTTP adapter.
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("app/tingyu/data/bili/**", "app/tingyu/model/Models.kt", "app/tingyu/model/MusicMetadata.kt",
            "app/tingyu/download/DownloadRules.kt", "app/tingyu/debug/UaComparison.kt",
            "app/tingyu/debug/NetworkProbe.kt", "app/tingyu/debug/DebugSettings.kt",
            "app/tingyu/probe/**")
    }
    sourceSets.test {
        kotlin.srcDir("../app/src/test/java")
        kotlin.include("app/tingyu/bili/**", "app/tingyu/debug/DebugSettingsTest.kt",
            "app/tingyu/debug/NetworkProbeTest.kt", "app/tingyu/download/DownloadRulesTest.kt",
            "app/tingyu/model/TrackTest.kt", "app/tingyu/model/MusicMetadataTest.kt")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application { mainClass.set("app.tingyu.probe.MainKt") }
