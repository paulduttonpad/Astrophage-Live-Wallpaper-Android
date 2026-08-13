plugins {
    id("com.android.application")
}

android {
    namespace = "com.plvsultra.astrophage.wallpaper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.plvsultra.astrophage.wallpaper"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
}
