plugins {
    alias(mihonx.plugins.android.library)
}

android { namespace = "app.manyuenext.cloud.common" }

dependencies {
    compileOnly(projects.sourceApi)
    implementation(libs.okhttp.core)
    implementation(libs.injekt)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(projects.sourceApi)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("org.json:json:20240303")
}
