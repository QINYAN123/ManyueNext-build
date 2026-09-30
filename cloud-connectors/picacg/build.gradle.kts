plugins {
    alias(mihonx.plugins.android.application)
}

android {
    namespace = "app.manyuenext.cloud.picacg"
    defaultConfig { applicationId = "app.manyuenext.cloud.picacg"; versionCode = 1; versionName = "1.0.0" }
}

dependencies {
    implementation(projects.cloudConnectors.embedded)
    compileOnly(projects.sourceApi)
    testImplementation(projects.sourceApi)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("org.json:json:20240303")
}
