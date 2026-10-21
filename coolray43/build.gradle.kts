import java.util.Properties

plugins { id("com.android.application") }
val authentication = providers.environmentVariable("COORLAY_AUTH_ASSETS_DIR").orNull
    ?.let { file(it).canonicalFile }
    ?: rootProject.file("build-inputs/runtime-assets").canonicalFile
val signingStore = providers.environmentVariable("COORLAY_SIGNING_STORE_FILE").orNull
    ?.let { file(it).canonicalFile }
    ?: rootProject.file("build-inputs/signing/coorlay.keystore").canonicalFile
// storePassword, keyAlias and keyPassword of the keystore; without this file the debug-keystore defaults apply.
val signingProperties = (providers.environmentVariable("COORLAY_SIGNING_PROPERTIES").orNull
    ?.let { file(it).canonicalFile }
    ?: rootProject.file("build-inputs/signing/signing.properties").canonicalFile)
    .takeIf { it.isFile }
    ?.let { Properties().apply { it.inputStream().use { input -> load(input) } } }
android {
    namespace = "com.coorlay.coolray"
    compileSdk = 37
    buildFeatures { buildConfig = true }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    defaultConfig {
        applicationId = "com.coorlay"
        minSdk = 18
        targetSdk = 22
        versionCode = 9
        versionName = "1.0.4-debug"
        multiDexEnabled = true
        // ClusterMediaTest's relay runs in the test package's own process, which loads only the main DEX.
        multiDexKeepProguard = file("multidex-test-keep.pro")
        // Test builds ("-debug") keep the detailed journal on; releases start with it off.
        val testBuild = versionName.orEmpty().endsWith("-debug")
        buildConfigField("boolean", "DETAILED_LOGS_DEFAULT", testBuild.toString())
        testInstrumentationRunner = "com.coorlay.coolray.CoolrayTestRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "x86", "x86_64") }
    }
    sourceSets.getByName("main").assets.srcDir(authentication)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs.getByName("debug") {
        storeFile = signingStore
        signingProperties?.let {
            storePassword = it.getProperty("storePassword")
            keyAlias = it.getProperty("keyAlias")
            keyPassword = it.getProperty("keyPassword")
        }
        enableV1Signing = true; enableV2Signing = true
    }
    buildTypes.getByName("release") {
        // The shipped APK. R8 keeps only the BouncyCastle/Kotlin/JmDNS code coorlay calls, so the
        // API-18 Dalvik extracts and optimizes far less DEX after every update. Same key as debug,
        // so it installs over earlier coorlay builds. Not obfuscated: journals keep real class names.
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // A release writes nothing to logcat: the firmware's logTool.sh copies logcat to the flash all the time.
        if (!defaultConfig.versionName.orEmpty().endsWith("-debug")) proguardFiles("proguard-nolog.pro")
    }
    // Factory API-18 installation, not a Play Store publication.
    lint { abortOnError = true; disable += "ExpiredTargetSdkVersion" }
}
dependencies {
    implementation(project(":legacy-core"))
    implementation("androidx.multidex:multidex:2.0.1")
    // 2.5.2 supports API 9+. Later releases require newer Android than the Coolray's API 18.
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
val verifyAuthentication by tasks.registering {
    doLast {
        check(authentication.isDirectory) { "MFi assets directory is missing: $authentication" }
        check(signingStore.isFile) { "APK signing keystore is missing: $signingStore" }
        check(listOf("identity.pk8", "certificate.p7b").all {
            authentication.resolve("offline-mfi/$it").let { path -> path.isFile && path.length() > 0 }
        }) { "Required MFi input is missing" }
    }
}
tasks.named("preBuild") { dependsOn(verifyAuthentication) }
