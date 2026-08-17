plugins {
    id("com.android.application")
}

android {
    namespace = "com.paddigital.astrophagelivewallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paddigital.astrophagelivewallpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
