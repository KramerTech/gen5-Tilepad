plugins { id("com.android.application") }
android {
    namespace = "com.tilepad.tweaks"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tilepad.tweaks"
        minSdk = 19
        targetSdk = 19
        versionCode = 3
        versionName = "1.1.1"
    }
    sourceSets["main"].java.srcDir("../tweaks-core/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = true
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
