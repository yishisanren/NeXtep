plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.lujinxin.nextep"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.yishisanren.panes"
        minSdk = 35
        targetSdk = 36
        versionCode = 83
        versionName = "2.3.3"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // The framework provides this API at runtime; it must never be packaged in the APK.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
