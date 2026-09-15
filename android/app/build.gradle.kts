plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.phoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.phoneagent"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

/**
 * Ship the operator guide inside the APK.
 *
 * `skills/phone-agent/SKILL.md` is the single source of truth for how to drive this
 * app, and the console's SKILL tab serves it. Copying it here at build time is what
 * turns "update the docs when you update a feature" from a habit into a build step —
 * a guide that silently drifts from the binary it describes is worse than no guide,
 * because it is trusted.
 */
val syncSkillDoc = tasks.register<Copy>("syncSkillDoc") {
    from(rootProject.file("../skills/phone-agent/SKILL.md"))
    into(layout.projectDirectory.dir("src/main/assets/webui"))
}

tasks.named("preBuild") {
    dependsOn(syncSkillDoc)
}

// Almost entirely framework-only. The one exception is on-device OCR: ML Kit's
// `com.google.mlkit:*` artifacts bundle their models into the APK and do NOT
// require Google Play Services, which matters on devices without GMS.
// (The `com.google.android.gms:play-services-mlkit-*` variants would not work here.)
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
