import java.util.Properties
import java.security.MessageDigest
import java.io.File

plugins {
    id("com.android.application")
}

// 发布签名：读取 android/keystore/keystore.properties（已 gitignore，见 README）
val ksProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Pack.py owns this mutable pair.  APK assets instead consume this task's
// build-private, hash-verified snapshot, so a packer rename cannot make asset
// merging re-read a different archive after validation.
val runtimeSourceDir = rootProject.file("runtime-assets/runtime")
val runtimeSourceManifest = File(runtimeSourceDir, "runtime.json")
val runtimeSourceArchive = File(runtimeSourceDir, "runtime.tar.xz")
val runtimeAssetsRoot = layout.buildDirectory.dir("generated/runtime-assets")
val runtimeSnapshotDir = runtimeAssetsRoot.map { it.dir("runtime") }
val runtimeSnapshotManifest = runtimeSnapshotDir.map { it.file("runtime.json") }
val runtimeSnapshotArchive = runtimeSnapshotDir.map { it.file("runtime.tar.xz") }
val runtimeSnapshotTool = rootProject.file("tools/runtime/snapshot_runtime.py")
val prepareRuntimeAssets = tasks.register<Exec>("prepareRuntimeAssets") {
    inputs.files(runtimeSourceManifest, runtimeSourceArchive)
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
    inputs.file(runtimeSnapshotTool).withPathSensitivity(PathSensitivity.ABSOLUTE)
    outputs.file(runtimeSnapshotManifest)
    outputs.file(runtimeSnapshotArchive)
    commandLine(
        "python3",
        runtimeSnapshotTool.absolutePath,
        "--manifest", runtimeSourceManifest.absolutePath,
        "--archive", runtimeSourceArchive.absolutePath,
        "--output", runtimeSnapshotDir.get().asFile.absolutePath,
    )
}

android {
    namespace = "com.personaldashboard.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.personaldashboard.core"
        minSdk = 29
        targetSdk = 35
        versionCode = 20006
        versionName = "2.0.0-core.6"
        testInstrumentationRunner = "com.personaldashboard.app.PluginDeviceTest"
        // 内置运行时（PRoot + Debian arm64 + Node + dsh）只支持 arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-acceptance"
            resValue("string", "app_name", "鱼缸测试")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; resValues = true }
    // 核心静态 assets 保留在 src/main/assets；runtime 只消费构建私有稳定快照。
    sourceSets["main"].assets.directories.addAll(listOf("src/main/assets", runtimeAssetsRoot.get().asFile.absolutePath))
    androidResources { noCompress += listOf("xz") }
    // PRoot 需要以可执行文件形式落在 nativeLibraryDir（Android 10+ 不允许从数据目录 exec）
    packaging { jniLibs.useLegacyPackaging = true }
    packaging { resources.excludes += listOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin") }
    lint { abortOnError = true; checkReleaseBuilds = true }
}

dependencies {
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Both installable variants consume the generated output, and preBuild must
// inspect exactly that output.  Declared file inputs also make a replaced
// runtime invalidate an otherwise UP-TO-DATE prepare task.
tasks.matching {
    it.name == "preBuild" || it.name == "preReleaseBuild" ||
        it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets"
}.configureEach { dependsOn(prepareRuntimeAssets) }

// Neither a release nor an installable debug APK may reuse a stale runtime.
tasks.matching { it.name == "preReleaseBuild" || it.name == "preDebugBuild" }.configureEach {
    doFirst {
        val manifest = runtimeSnapshotManifest.get().asFile
        val archive = runtimeSnapshotArchive.get().asFile
        if (!manifest.isFile || !archive.isFile) throw GradleException("Build the pinned runtime before assembling a release")
        val metadata = groovy.json.JsonSlurper().parse(manifest) as Map<*, *>
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != metadata["sha256"]) throw GradleException("Runtime archive does not match its manifest")
        for (name in listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so")) {
            if (!file("src/main/jniLibs/arm64-v8a/$name").isFile) throw GradleException("Missing pinned native dependency: $name")
        }
        if (!file("src/main/assets/third-party-notices.html").isFile) throw GradleException("Missing offline third-party notices")
        val notice = groovy.json.JsonSlurper().parse(file("src/main/assets/third-party-inventory.json")) as Map<*, *>
        val sourceRoot = rootProject.projectDir.parentFile
        val runtimeRoot = File(sourceRoot, "android/tools/runtime")
        val runtimeSources = listOf("runtime-lock.json", "package.json", "package-lock.json", "pack.py", "fixed_workspace.py", "fetch-runtime.py", "fetch.sh", "build-runtime.sh", "runtime_inputs.py", "snapshot_runtime.py").map { File(runtimeRoot, it) } +
            File(runtimeRoot, "overlay").walkTopDown().filter { source ->
                source.isFile && source.relativeTo(runtimeRoot).invariantSeparatorsPath.split('/').none {
                    it.startsWith("._") || it == ".DS_Store" || it == "__MACOSX"
                }
            }.toList()
        val runtimeInputs = metadata["inputs"] as? Map<*, *> ?: throw GradleException("Runtime has no source input record; rebuild it")
        if (runtimeInputs.keys != runtimeSources.map { it.relativeTo(sourceRoot).invariantSeparatorsPath }.toSet())
            throw GradleException("Runtime source set changed; rebuild it")
        runtimeSources.forEach { source ->
            val key = source.relativeTo(sourceRoot).invariantSeparatorsPath
            val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != runtimeInputs[key]) throw GradleException("Runtime source changed; rebuild it: $key")
        }
        (notice["inputs"] as Map<*, *>).forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(File(sourceRoot, path.toString()).readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expected) throw GradleException("Dependency locks changed; regenerate notices: $path")
        }
        val runtimeClasspath = if (name == "preDebugBuild") "debugRuntimeClasspath" else "releaseRuntimeClasspath"
        val resolved = configurations.getByName(runtimeClasspath).resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() }.sorted()
        if (resolved != notice["androidDependencies"]) throw GradleException("Android dependencies changed; regenerate notices")

    }
}

// Inspect every configured asset root, including generated runtime assets.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val forbiddenAssets = setOf(
            "www/js/nas.js",
            "www/js/mac-usage.js",
            "plugin-framework/v1/js/nas.js",
            "plugin-framework/v1/js/mac-usage.js",
            "plugin-framework/v1/js/local-quota.js",
            "plugin-framework/v1/js/monitor-settings.js",
        )
        android.sourceSets.flatMap { it.assets.directories.map { path -> file(path) } }.distinct().forEach { root ->
            if (root.exists()) root.walkTopDown().filter { it.isFile }.forEach { asset ->
                val relative = asset.relativeTo(root).invariantSeparatorsPath
                val parts = relative.split('/')
                val privateData = parts.any { it in setOf("BOT", "PLUGINS", "CONFIG", "shared_prefs", "recovery", "sessions") }
                if (relative.startsWith("plugins/") || privateData || relative in forbiddenAssets ||
                    asset.name in setOf("registry.json", "bots.json", "workspace.json"))
                    throw GradleException("APK 不能包含业务插件或个人数据：$relative")
            }
        }
    }
}
