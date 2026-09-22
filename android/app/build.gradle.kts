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
        versionCode = 4
        versionName = "0.3.0"

        // Only arm64.
        //
        // ONNX Runtime ships a native library per ABI and each is ~25MB. Carrying all
        // four took the APK from 51MB to 149MB for slices no supported device would
        // load — every phone this runs on is arm64-v8a. The x86 slices matter only for
        // emulators, which is not the deployment target.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

/**
 * Bundle the MCP server so the phone can hand it out over the LAN.
 *
 * The /mcp endpoints exist so another machine needs nothing but a browser: open the
 * address, download the bridge, paste one config block, and it is driving the phone.
 * Shipping these files inside the APK is what makes that work without a repo checkout
 * on the client machine.
 */
val syncMcpFiles = tasks.register<Copy>("syncMcpFiles") {
    from(rootProject.file("../pc/mcp-server/index.mjs"))
    from(rootProject.file("../pc/mcp-server/README.md"))
    from(rootProject.file("../tools/test-mcp.mjs"))
    into(layout.projectDirectory.dir("src/main/assets/mcp"))
}

tasks.named("preBuild") {
    dependsOn(syncSkillDoc, syncMcpFiles)
}

// Two OCR engines during the transition.
//
// ML Kit stays as the fallback: it is fast, already wired in, and handles Latin text
// perfectly well. PP-OCR is added because ML Kit's Chinese recogniser garbles
// small coloured text over photographs often enough to matter — measured on a real
// menu, 28% of product titles came back wrong or unreadable, and it is not
// deterministic between runs on the same frame.
//
// `onnxruntime-android` rather than `-mobile`: the mobile artifact disappeared from
// Maven Central at 1.20 and the standard one is ABI-split by the build, so the APK
// only carries the slices it needs.
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
