import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val tokenizerOutput = layout.buildDirectory.dir("native-tokenizer")
val tokenizerInputs = rootProject.fileTree("native/discovery-tokenizer") { exclude("target/**") }
val buildHostTokenizer = tasks.register<Exec>("buildHostDiscoveryTokenizer") {
    inputs.files(tokenizerInputs, rootProject.file("tools/build-discovery-tokenizer.ps1"))
    outputs.file(tokenizerOutput.map { it.file("host/discovery_tokenizer.dll") })
    commandLine("pwsh", "-NoProfile", "-File", rootProject.file("tools/build-discovery-tokenizer.ps1"),
        "-OutputDirectory", tokenizerOutput.get().asFile, "-HostOnly")
}
val buildAndroidTokenizer = tasks.register<Exec>("buildAndroidDiscoveryTokenizer") {
    inputs.files(tokenizerInputs, rootProject.file("tools/build-discovery-tokenizer.ps1"))
    outputs.dir(tokenizerOutput.map { it.dir("jniLibs") })
    commandLine("pwsh", "-NoProfile", "-File", rootProject.file("tools/build-discovery-tokenizer.ps1"),
        "-OutputDirectory", tokenizerOutput.get().asFile)
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostTokenizer)
    systemProperty("discovery.tokenizer.library", tokenizerOutput.get().file("host/discovery_tokenizer.dll").asFile.absolutePath)
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildAndroidTokenizer) }
val gemmaTestAssets = tasks.register<Copy>("generateGemmaTestAssets") {
    from(rootProject.file("tools/semantic-discovery")) { include("gemma2-android-reference.json", "gemma2-q4-android-reference.json", "litert-tokenizer-reference.json") }
    into(layout.buildDirectory.dir("generated/gemmaTestAssets"))
}
tasks.matching { it.name == "preDebugAndroidTestBuild" || it.name == "preReleaseAndroidTestBuild" }.configureEach { dependsOn(gemmaTestAssets) }

tasks.register<JavaExec>("verifyGemmaTokenizer") {
    group = "verification"
    dependsOn("compileDebugUnitTestKotlin", buildHostTokenizer)
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.GemmaTokenizerParity")
    workingDir(rootProject.projectDir)
    systemProperty("discovery.tokenizer.library", tokenizerOutput.get().file("host/discovery_tokenizer.dll").asFile.absolutePath)
    args(providers.gradleProperty("gemmaModelBundle").getOrElse(""), rootProject.file("tools/semantic-discovery/litert-tokenizer-reference.json"))
}


// Credentials are resolved before the android block, because script property initialisers run in
// declaration order and defaultConfig reads these values.
val notionEnvFile = parseEnvFile(rootProject.file(".env"))

val notionToken = credential(
    keys = listOf("NOTION_TOKEN", "NOTION_API_KEY", "NOTION_API_SECRET"),
    bareValues = notionEnvFile.bareValues.filter { looksLikeNotionToken(it) }
)

val notionPageId = credential(
    keys = listOf("NOTION_PAGE_ID", "NOTION_PAGE_URL"),
    bareValues = notionEnvFile.bareValues.filter { looksLikeNotionPageReference(it) }
)

logger.lifecycle(
    "Notion credentials: token=${if (notionToken.isBlank()) "not configured" else "configured"}, " +
        "page=${if (notionPageId.isBlank()) "auto-discover (most recently edited page)" else "configured"}"
)

if (notionToken.isNotBlank() && notionToken.any { it.isWhitespace() }) {
    logger.warn("The configured Notion token contains whitespace, which tokens never do. Check local.properties / .env.")
}

if (notionToken.isBlank() && notionEnvFile.bareValues.isNotEmpty()) {
    logger.warn("Found a bare value in .env that does not look like a Notion token; it was ignored.")
}

android {
    namespace = "com.example.notionreader"
    compileSdk = 37
    // Preserve replacement behavior for the legacy installer too.
    installation { installOptions.add("-r") }
    sourceSets.getByName("main").jniLibs.srcDir(tokenizerOutput.get().dir("jniLibs").asFile)
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/gemmaTestAssets").get().asFile)

    defaultConfig {
        applicationId = "com.example.notionreader"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36

        versionCode = 1
        versionName = "0.1"

        // Required before the first instrumented test can run at all: the default runner cannot
        // discover AndroidX test rules.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Development-only credentials, read from uncommitted files (see notionCredentials below).
        // WARNING: values in BuildConfig are compiled into the APK, so they are only acceptable for
        // local development. A shipped app must fetch the token at runtime instead.
        buildConfigField("String", "NOTION_TOKEN", quote(notionToken))
        buildConfigField("String", "NOTION_PAGE_ID", quote(notionPageId))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // **No test orchestrator.** This is a deliberate reversal of an earlier decision, so both
        // halves of it are recorded here.
        //
        // Why it was added: this suite is Compose-under-instrumentation, and a transient device-side
        // process death was observed to abort a run. The runner reported "Process crashed", and the
        // resulting XML then disagreed with the HTML report about how many tests had even run, so
        // neither could be trusted. One process per test meant a bad invocation failed exactly one test
        // and the rest still executed.
        //
        // Why it was removed: the second half of that justification is now handled by name rather than
        // prevented. `tools/run-accessibility-tests.ps1` counts the `@Test` annotations in the sources
        // and reports **INCOMPLETE**, not PASSED, whenever fewer tests ran than exist - whatever the
        // cause. And the first half was bought with a process start per test: 180 of them, on an
        // emulator whose process budget the orchestrator itself competes for. That is the suspicion
        // measured in docs/decisions.md §23.32.
        //
        // What is given up, stated plainly so it is not rediscovered as a surprise: with a single
        // instrumentation process, a device-side crash can take more than one test with it, and state
        // can outlive the test that made it. Neither is load-bearing here - no test drives the real
        // `MainActivity`, because they render `ComponentActivity` with in-memory doubles built by
        // `ShellHarness`, so no on-disk session state is reachable from a test at all.
        //
        // If process deaths return, reinstate `execution = "ANDROIDX_TEST_ORCHESTRATOR"` (and the
        // `androidTestUtil` dependency below) rather than widening a timeout - §23.31 is what widening
        // a timeout instead of asking the question costs.

        unitTests.all {
            it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        }
    }
}

/**
 * Credential lookup for local development.
 *
 * Sources, in order:
 *  1. `local.properties` (`NOTION_TOKEN=...`) — the Android convention, never committed.
 *  2. `.env` in the repository root, in either form:
 *     - dotenv style: `NOTION_TOKEN=ntn_...` (also accepts `export ` prefixes, quotes and `#` comments)
 *     - a bare value with no key at all: `.env` containing nothing but the token itself, which is how
 *       Notion's "copy integration token" output and most secret files in the wild look.
 *
 * A bare value is classified by shape: Notion tokens start with `ntn_`/`secret_`, page IDs and page URLs
 * are 32 hex characters (dashed or not) or contain a notion.so URL. Anything unrecognised is ignored so
 * the build never fails and the app simply keeps its hard-coded demo document.
 */
data class EnvContents(val values: Map<String, String>, val bareValues: List<String>)

/**
 * Parses a dotenv-style file leniently. Missing keys, CRLF line endings, a UTF-8 BOM, `export `
 * prefixes, surrounding quotes and `#` comments are all tolerated, as is a file that holds a single
 * bare value with no key at all.
 */
fun parseEnvFile(file: File): EnvContents {
    if (!file.exists()) return EnvContents(emptyMap(), emptyList())

    val values = mutableMapOf<String, String>()
    val bareValues = mutableListOf<String>()

    file.readLines().forEach { rawLine ->
        val line = rawLine.removePrefix("\uFEFF").trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach

        val separator = line.indexOf('=')
        if (separator < 0) {
            line.trim('"', '\'').takeIf { it.isNotEmpty() }?.let(bareValues::add)
            return@forEach
        }

        val key = line.substring(0, separator).removePrefix("export ").trim()
        val value = line.substring(separator + 1).trim().trim('"', '\'').trim()
        if (key.isNotEmpty() && value.isNotEmpty()) values[key] = value
    }

    return EnvContents(values, bareValues)
}

/** Looks a credential up in `local.properties`, then `.env` keys, then the given bare `.env` values. */
fun credential(keys: List<String>, bareValues: List<String> = emptyList()): String {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { stream -> properties.load(stream) }
        keys.forEach { key ->
            properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
    }

    keys.forEach { key ->
        notionEnvFile.values[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }

    return bareValues.firstOrNull().orEmpty()
}

/** Notion integration tokens: current `ntn_` format and the legacy `secret_` format. */
fun looksLikeNotionToken(value: String): Boolean {
    val trimmed = value.trim()
    return (trimmed.startsWith("ntn_") || trimmed.startsWith("secret_")) && !trimmed.contains(' ')
}

/** A page UUID (dashed or not) or a notion.so page URL. */
fun looksLikeNotionPageReference(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.contains("notion.so") || trimmed.contains("notion.site")) return true
    return Regex("^[0-9a-fA-F-]{32,36}$").matches(trimmed)
}

/** Escapes a value for use as a Kotlin string literal in BuildConfig. */
fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

dependencies {
    // Stable compatible release; bounded ordinary workers, no foreground/expedited execution.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // Official embedding API, pinned for reproducible published-bundle inference.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // viewModelScope, and the ViewModel that owns the listening session. The session's lifetime has to be
    // the task's rather than the Activity's: with no `android:configChanges`, every configuration change
    // rebuilds the Activity, and a session owned by one died with it (see ReaderViewModel).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Background playback. Media3's `MediaSessionService` is the platform's documented answer to Android
    // 17's background-audio hardening, which applies to every app on API 37+ whether or not it targets 37:
    // audio interacted with while the app has no visible activity must come from a foreground service of a
    // media type, and every failure in that area is SILENT (playback suppressed with no exception, focus
    // returning a failure code rather than throwing). The library manages that lifecycle, which is why it is
    // used rather than a hand-written foreground service.
    //
    // `media3-session` pulls `media3-common`, which is where `Player`, `Commands`, `MediaItem` and
    // `SimpleBasePlayer` come from — the last of these being the documented base for "a custom player that
    // you want to connect to a MediaSession", which is exactly this app: the playback engine is TTS, not a
    // media decoder, so there is nothing for ExoPlayer to do.
    implementation("androidx.media3:media3-session:1.11.1")

    // Ktor 3.6.0 or newer: 3.2.0 ships an identifier containing spaces that R8 cannot dex for minSdk 26.
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The spec's repository tests require a fake HTTP layer, not a fake API: only that exercises the
    // real headers, retry and error-mapping code.
    testImplementation("io.ktor:ktor-client-mock:3.6.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Pinned deliberately. Compose's ui-test-junit4 pulls in Espresso 3.5.0 transitively, which cannot
    // run on this device (Android 17 / API 37): its idle path reflects on
    // android.hardware.input.InputManager.getInstance(), which is a hidden method there, so every test
    // fails in Espresso.onIdle before reaching an assertion. 3.7.0 is the current release and does not.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // No `androidTestUtil("androidx.test:orchestrator:...")`: without `execution =
    // "ANDROIDX_TEST_ORCHESTRATOR"` above it would be installed on every run and never invoked. See
    // the `testOptions` block for why the orchestrator was removed and how it would come back.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


// Explicit developer-only task: private snapshots remain outside normal tests and APKs.
tasks.register<JavaExec>("privateDiscoveryReview") {
    group = "verification"
    description = "Review/replay an explicitly exported private snapshot under ignored build storage"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.PrivateDiscoveryReview")
    workingDir(rootProject.projectDir)
    args(providers.gradleProperty("discoveryReviewDirectory").getOrElse(""),
        providers.gradleProperty("discoveryReviewRounds").getOrElse("32"),
        providers.gradleProperty("discoveryReviewSeconds").getOrElse("60"))
}

tasks.register<JavaExec>("reviewDiscoveryEngine") {
    group = "verification"
    description = "Review the sole engine on cache-current records; export private evidence without quality claims"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.DiscoveryEngineReview")
    workingDir(rootProject.projectDir)
    args(providers.gradleProperty("discoveryReviewDirectory").getOrElse(""))
}

tasks.register<JavaExec>("exportPublicCorpusPassages") {
    group = "verification"
    description = "Export pinned public corpus through the shipping tokenizer/chunker for host comparisons"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.PublicCorpusInput")
    workingDir(rootProject.projectDir)
    args(providers.gradleProperty("publicCorpusDirectory").getOrElse(""),
        providers.gradleProperty("publicCorpusLock").getOrElse(""),
        providers.gradleProperty("publicCorpusVocabulary").getOrElse(""))
}

tasks.register<JavaExec>("exportPublicFormationInput") {
    group = "verification"
    description = "Export identical shipping formation opportunities from verified public vectors"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.PublicFormationInput")
    workingDir(rootProject.projectDir)
    maxHeapSize = "2g"
    args(providers.gradleProperty("publicCorpusDirectory").getOrElse(""),
        providers.gradleProperty("publicVectorDirectory").getOrElse(""),
        providers.gradleProperty("publicFormationDirectory").getOrElse(""))
}

tasks.register<JavaExec>("evaluatePublicExactFormation") {
    group = "verification"
    description = "Observe shipping exact seed formation on the verified shared public opportunities"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.PublicExactFormation")
    workingDir(rootProject.projectDir)
    maxHeapSize = "2g"
    // Developer exhaustive oracle; never changes the application's engine selection.
    systemProperty("discovery.formationBounds", providers.gradleProperty("publicFormationBoundsEnabled").getOrElse("true"))
    args(providers.gradleProperty("publicCorpusDirectory").getOrElse(""),
        providers.gradleProperty("publicVectorDirectory").getOrElse(""),
        providers.gradleProperty("publicFormationDirectory").getOrElse(""))
    if (providers.gradleProperty("publicFormationDistance").isPresent || providers.gradleProperty("publicMembershipThreshold").isPresent) {
        args(providers.gradleProperty("publicFormationDistance").getOrElse("0.35"),
            providers.gradleProperty("publicMembershipThreshold").getOrElse("0.60"))
    }
}

tasks.register<JavaExec>("evaluatePublicGraphProjection") {
    group = "verification"
    description = "Apply shipping downstream policy to verified host graph communities"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("com.example.notionreader.tooling.PublicGraphProjection")
    workingDir(rootProject.projectDir)
    maxHeapSize = "2g"
    args(providers.gradleProperty("publicCorpusDirectory").getOrElse(""),
        providers.gradleProperty("publicVectorDirectory").getOrElse(""),
        providers.gradleProperty("publicFormationDirectory").getOrElse(""),
        providers.gradleProperty("publicGraphReport").getOrElse(""),
        providers.gradleProperty("publicMembershipThreshold").getOrElse("0.60"))
}
