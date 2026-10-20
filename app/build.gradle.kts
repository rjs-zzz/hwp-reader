import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

var releaseSigningMissing = false

android {
    namespace = "io.github.rjszzz.hwpreader"
    // 최신 AndroidX 가 컴파일 SDK 37 이상을 요구한다. 동작 기준인 targetSdk 는 PRD 6장대로 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rjszzz.hwpreader"
        minSdk = 26
        targetSdk = 36
        // 배포할 때마다 versionCode 를 1 올린다(docs/RELEASE.md)
        versionCode = 2
        versionName = "1.0.1"
    }

    // 릴리스 서명 키 — 키 파일과 비밀번호는 저장소 밖에 둔다(docs/RELEASE.md).
    //   keystore.properties(저장소 루트, git 제외): storeFile(절대 경로) · storePassword · keyAlias · keyPassword
    // 이 파일이 없으면 릴리스 빌드를 멈춘다 — 디버그 키로 서명된 파일이 정식 배포본으로 나가지 않게.
    // 시험용으로 디버그 키 서명이 필요하면 -PallowDebugSigning=true 를 붙인다.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = when {
        keystoreFile.exists() -> {
            val props = Properties().apply { keystoreFile.inputStream().use(::load) }
            fun required(key: String) = props.getProperty(key)?.takeIf { it.isNotBlank() }
                ?: throw GradleException("keystore.properties 에 '$key' 가 없다 (docs/RELEASE.md)")
            signingConfigs.create("release") {
                storeFile = rootProject.file(required("storeFile"))
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
        providers.gradleProperty("allowDebugSigning").orNull == "true" -> signingConfigs.getByName("debug")
        else -> null
    }
    releaseSigningMissing = releaseSigning == null

    buildTypes {
        release {
            // R8 난독화·축소 (PRD SEC-7)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

// kordoc 뷰어 번들(web/) → src/main/assets/viewer. Node 20 이상이 필요하다.
val webDir = rootProject.layout.projectDirectory.dir("web")
val buildViewer = tasks.register<Exec>("buildViewer") {
    description = "Bundles kordoc viewer into app assets"
    workingDir = webDir.asFile
    val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.dir(webDir.dir("shims"))
    inputs.dir(webDir.dir("public"))
    inputs.files(webDir.file("build.mjs"), webDir.file("package-lock.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/viewer"))
}
tasks.named("preBuild") { dependsOn(buildViewer) }

// 정식 서명 키가 없으면 릴리스 빌드를 멈춘다
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    description = "Fails the release build when keystore.properties is missing"
    val missing = releaseSigningMissing
    doLast {
        if (missing) {
            throw GradleException(
                "릴리스 서명 키가 없다. 저장소 루트에 keystore.properties 를 두거나(docs/RELEASE.md), " +
                    "시험용이면 -PallowDebugSigning=true 를 붙인다.",
            )
        }
    }
}

/**
 * 병합된 매니페스트에 권한이 없는지 확인한다 (PRD SEC-1·SEC-2).
 * 라이브러리를 더하다 INTERNET 등이 섞여 들어오면 빌드를 멈춘다.
 * androidx.core 가 넣는 앱 내부용 서명 권한(DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION)만 허용한다.
 */
abstract class CheckNoPermissions : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*android:name="([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
            .toList()
        if (declared.isNotEmpty()) {
            throw GradleException("SEC-1·SEC-2 위반 — 병합 매니페스트에 권한이 들어왔다: $declared")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val checkPermissions = tasks.register<CheckNoPermissions>("check${name}Permissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.configureEach {
            if (this.name == "assemble$name" || this.name == "bundle$name") dependsOn(checkPermissions)
            if (variant.buildType == "release" && this.name == "pre${name}Build") dependsOn(checkReleaseSigning)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
