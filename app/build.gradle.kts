import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
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

    defaultConfig {
        applicationId = "com.example.notionreader"
        minSdk = 26
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
        // Each instrumented test runs in its own instrumentation invocation.
        //
        // This suite is Compose-under-instrumentation on a real phone, and a single transient,
        // device-side process death was observed to abort the run: the runner reported
        // "Process crashed", and the resulting XML then disagreed with the HTML report about how many
        // tests had even run, so the results could not be trusted either way. Isolating each test
        // means one bad invocation fails exactly one test and the rest still execute.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
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
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

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
    androidTestUtil("androidx.test:orchestrator:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
