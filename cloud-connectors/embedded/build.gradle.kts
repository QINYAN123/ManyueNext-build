plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "app.manyuenext.cloud.embedded"
}

dependencies {
    api(projects.cloudConnectors.common)
    compileOnly(projects.sourceApi)
}
