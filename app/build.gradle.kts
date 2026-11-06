plugins { id("com.android.application") }
android {
    namespace = "com.tilepad"
    sourceSets["main"].java.srcDir("../tweaks-core/src/main/java")
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tilepad"
        minSdk = 19
        targetSdk = 19
        versionCode = 5
        versionName = "1.1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint {
        // This is a locally installed app for an Android 4.4 vehicle, not Play.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = true
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
