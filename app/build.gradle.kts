import java.util.Properties
import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        create("grpc") {
            artifact = libs.grpc.gen.java.get().toString()
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {}
            }
            task.plugins {
                create("grpc") {}
            }
        }
    }
}

fun computeVersionCode(versionName: String): Int {
    val clean = versionName.trim().removePrefix("v").removePrefix("V")
    val withoutBuild = clean.substringBefore('+')
    val core = withoutBuild.substringBefore('-').split('.')
    val major = core.getOrNull(0)?.toIntOrNull() ?: 1
    val minor = core.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = core.getOrNull(2)?.toIntOrNull() ?: 0
    val prerelease = withoutBuild.substringAfter('-', "").ifBlank { null }

    // Keep versionCode strictly increasing across prerelease channels and the
    // final stable release. The previous core-only layout gave alpha.N,
    // beta.N, rc.N, and stable the same versionCode.
    val base = major.toLong() * 10_000_000L + minor.toLong() * 100_000L + patch.toLong() * 1_000L
    val stage = if (prerelease == null) {
        999L
    } else {
        val match = Regex("""(?i)(alpha|beta|rc)[.-]?(\d+)?""").find(prerelease)
        val channel = when (match?.groupValues?.get(1)?.lowercase()) {
            "alpha" -> 1L
            "beta" -> 2L
            "rc" -> 3L
            else -> 0L
        }
        val sequence = match?.groupValues?.get(2)?.toLongOrNull() ?: 0L
        channel * 100L + sequence.coerceIn(0L, 99L)
    }
    return (base + stage).coerceIn(1L, 2_100_000_000L).toInt()
}

val versionProps = Properties().apply {
    file("$rootDir/version.properties").useLines { lines ->
        lines.filter { it.startsWith("NDK_VERSION=") }
            .forEach { line ->
                setProperty(line.substringBefore('='), line.substringAfter('='))
            }
    }
}

val appVersionName = if (project.hasProperty("appVerName")) {
    project.property("appVerName").toString().trim().removePrefix("v").removePrefix("V")
} else {
    "1.0.0"
}

val appVersionCode = if (project.hasProperty("appVerCode")) {
    project.property("appVerCode").toString().toInt()
} else {
    computeVersionCode(appVersionName)
}

android {
    namespace = "com.simplexray.re"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.fallback.xm"
        versionCode = appVersionCode
        versionName = appVersionName
        targetSdk = 37
        minSdk = 34

        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("store.properties")
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }

            val ksPath = System.getenv("KEYSTORE_PATH") ?: props.getProperty("storeFile")
            if (!ksPath.isNullOrEmpty()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props.getProperty("storePassword") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("keyAlias") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("keyPassword") ?: ""
            }

            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            isDebuggable = true
            versionNameSuffix = "-debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Ship the supported native backends from their configured source directories.
            excludes += "**/libmipstun.so"
            excludes += "**/libmihomo.so"
        }
        resources {
            excludes += "META-INF/*.txt"
            excludes += "META-INF/*.version"
            excludes += "META-INF/**/*.txt"
            excludes += "**/*.proto"
            excludes += "**/aboutlibraries.json"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    ndkVersion = versionProps.getProperty("NDK_VERSION")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                (output as com.android.build.api.variant.VariantOutput).outputFileName.set("SimpleXray-Groups-arm64-v8a.apk")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rootProject.file("third_party/xray-prebuilt"))
            java {
                directories.add("src/main/java")
                directories.add("build/generated/source/proto/main/java")
            }
            kotlin {
                directories.add("src/main/kotlin")
                directories.add("build/generated/source/proto/main/grpckt")
            }
            assets {
                directories.clear()
                directories.add("src/main/assets")
            }
        }
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent)

    implementation(libs.lazycolumnscrollbar)
    implementation(libs.reorderable)
    implementation(libs.okhttp)
    implementation(libs.yaml)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.material)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.glass.icons)
    implementation(libs.scripta.editor)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val ensureXrayCore = tasks.register("ensureXrayCore") {
    group = "build"
    description = "Xray is supplied by the Windows toolchain in third_party/xray-prebuilt"
}

tasks.named("preBuild").configure {
    dependsOn(ensureXrayCore)
}

if (project.hasProperty("exportLicenses")) {
    apply(plugin = "com.mikepenz.aboutlibraries.plugin")
    configure<com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension> {
        collect {
            includePlatform = true
        }
        export {
            outputFile = rootProject.file("docs/aboutlibraries.json")
            prettyPrint = true
        }
    }

    tasks.register<Exec>("updateLicensesDoc") {
        dependsOn("exportLibraryDefinitions")
        workingDir(rootProject.projectDir)
        commandLine("python3", "tools/update_licenses.py")
    }
}
