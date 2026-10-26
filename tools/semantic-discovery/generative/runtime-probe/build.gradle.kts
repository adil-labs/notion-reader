// Match the Reader build's Kotlin compiler classpath; AGP's default 2.2 cannot read LiteRT-LM metadata.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") }
}
plugins {
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}
android {
    namespace = "com.example.generativediscoveryprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.generativediscoveryprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].jniLibs.directories.add(file("../../../../app/build/native-tokenizer/jniLibs").absolutePath)
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("probe-assets").get().asFile.absolutePath)
}
val prepareProbeAssets = tasks.register<Copy>("prepareProbeAssets") {
    from("..") { include("cases.json", "prompt-v3.txt", "prompt-v4.txt", "prompt-v5-label-only.txt", "prompt-v6.txt") }
    into(layout.buildDirectory.dir("probe-assets"))
}
abstract class PrepareProductionLabelSources : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val destinationDirectory: DirectoryProperty
    @TaskAction fun copyShippingSources() {
        project.sync {
            from(sourceDirectory) {
                include("com/example/notionreader/domain/**")
                include("com/example/notionreader/application/Discovery*.kt")
                listOf("TopicCoordinator", "TopicLabelCoordinator", "TopicRetrievalCoordinator", "ExactEmbeddingMemo", "ConnectionCatalogProjection")
                    .forEach { include("com/example/notionreader/application/$it.kt") }
                listOf("FileDocumentCache", "ReaderDocumentCodec", "FileSemanticIndexStore", "FileTopicStore", "StoredTopicGraph",
                    "FileConnectionCatalog", "FileTopicLabelStore", "FileTopicRetrievalStore").forEach { include("com/example/notionreader/data/local/$it.kt") }
                include("com/example/notionreader/data/document/DocumentCache.kt")
                include("com/example/notionreader/data/document/CachedDocument.kt")
                listOf("GemmaTopicLabeler", "TopicLabelResponse", "GenerativeModelPack", "GenerativeModelManager",
                    "LiteRtTopicLabelEngine", "LocalDiscoveryModelManager", "ModelArtifact", "GemmaTokenizer",
                    "GemmaModelPack", "GemmaEmbeddingProvider", "LiteRtBackendRuntime")
                    .forEach { include("com/example/notionreader/data/discovery/$it.kt") }
            }
            into(destinationDirectory)
        }
    }
}
val prepareProductionLabelSources = tasks.register<PrepareProductionLabelSources>("prepareProductionLabelSources") {
    sourceDirectory.set(layout.projectDirectory.dir("../../../../app/src/main/java"))
    destinationDirectory.set(layout.buildDirectory.dir("production-label-sources"))
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin!!.addGeneratedSourceDirectory(prepareProductionLabelSources) { it.destinationDirectory }
}

tasks.named("preBuild") { dependsOn(prepareProbeAssets, prepareProductionLabelSources) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
