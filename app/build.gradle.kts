plugins {
    id("com.android.application")
}

android {
    namespace = "com.paddigital.astrophagelivewallpaper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.paddigital.astrophagelivewallpaper"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.2"
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
