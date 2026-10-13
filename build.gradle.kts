plugins {
    alias(libs.plugins.android.application) apply false
}
// The legacy-core module imports the required shared protocol sources.
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
}
