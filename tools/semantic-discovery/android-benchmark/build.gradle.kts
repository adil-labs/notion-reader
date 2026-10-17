plugins {
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}
val shippingSources = tasks.register<Sync>("shippingSources") {
    from("../../../app/src/main/java") {
        include("com/example/notionreader/domain/**")
        include("com/example/notionreader/application/DiscoveryController.kt", "com/example/notionreader/application/TopicCoordinator.kt")
        include("com/example/notionreader/data/local/FileDocumentCache.kt", "com/example/notionreader/data/local/FileSemanticIndexStore.kt")
        include("com/example/notionreader/data/local/ReaderDocumentCodec.kt")
        include("com/example/notionreader/data/document/DocumentCache.kt", "com/example/notionreader/data/document/CachedDocument.kt")
        include("com/example/notionreader/data/discovery/OnnxEmbeddingProvider.kt", "com/example/notionreader/data/discovery/BertWordPieceTokenizer.kt")
    }
    into(layout.buildDirectory.dir("generated/shipping"))
}
val publicAssets = tasks.register<Sync>("publicAssets") {
    from("../../../app/build/discovery-feasibility/probe-assets") { include("model_qint8_arm64.onnx") }
    from("../../../app/src/test/resources/discovery") { include("vocab.txt") }
    into(layout.buildDirectory.dir("generated/assets"))
}
android {
    namespace = "com.example.discoverybenchmark"
    compileSdk = 37
    defaultConfig { applicationId = "com.example.discoverybenchmark"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1" }
    sourceSets["main"].kotlin.directories += "build/generated/shipping"
    sourceSets["main"].assets.srcDir("build/generated/assets")
}
tasks.named("preBuild") { dependsOn(shippingSources, publicAssets) }
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
