plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "com.davemorrissey.labs.subscaleview"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                targets("ssiv_crop")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/crop/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(libs.androidx.annotation)
    implementation(libs.image.decoder)
}
