plugins { id("com.android.library") }

// shared/ holds only the protocol sources this app compiles; the Android 4.3 implementations of
// media, network and USB live in legacy-core. Unused DiPlay code is not part of this repository.
val legacyProtocolSources = layout.buildDirectory.dir("generated/legacyProtocol")
val syncLegacyProtocol by tasks.registering(Sync::class) {
    from("../shared/src/main/java")
    into(legacyProtocolSources)
}
val legacyProtocolTests = layout.buildDirectory.dir("generated/legacyTests")
val syncLegacyTests by tasks.registering(Sync::class) {
    from("../shared/src/test/java")
    into(legacyProtocolTests)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.coorlay.legacy.core"
    compileSdk = 37
    ndkVersion = "23.2.8568313"
    defaultConfig {
        minSdk = 18
        multiDexEnabled = true
        ndk { abiFilters += listOf("armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild { ndkBuild { arguments += "APP_PLATFORM=android-18" } }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    sourceSets.getByName("main").kotlin.directories += legacyProtocolSources.get().asFile.absolutePath
    sourceSets.getByName("test").kotlin.directories += legacyProtocolTests.get().asFile.absolutePath
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    lint { abortOnError = true }
}
tasks.named("preBuild") { dependsOn(syncLegacyProtocol, syncLegacyTests) }
dependencies {
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
