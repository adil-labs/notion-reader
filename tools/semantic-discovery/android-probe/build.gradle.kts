plugins { id("com.android.application") version "9.4.0" }
android {
    namespace = "com.example.discoveryfeasibility"
    compileSdk = 37
    defaultConfig { applicationId = "com.example.discoveryfeasibility"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1" }
    sourceSets["main"].assets.srcDir("../../../app/build/discovery-feasibility/probe-assets")
}
dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0") }
