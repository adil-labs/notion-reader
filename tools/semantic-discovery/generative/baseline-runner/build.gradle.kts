plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets["main"].kotlin.apply {
        srcDir("../../../../app/src/main/java")
        include("com/example/generativebaseline/**", "com/example/notionreader/domain/**",
            "com/example/notionreader/data/discovery/GemmaTokenizer.kt",
            "com/example/notionreader/data/discovery/GemmaModelPack.kt",
            "com/example/notionreader/data/discovery/ModelArtifact.kt")
    }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
}
application { mainClass.set("com.example.generativebaseline.BaselineMainKt") }
