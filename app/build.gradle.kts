plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.rjszzz.hwpreader"
    // 최신 AndroidX 가 컴파일 SDK 37 이상을 요구한다. 동작 기준인 targetSdk 는 PRD 6장대로 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rjszzz.hwpreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildTypes {
        release {
            // R8 난독화·축소 (PRD SEC-7)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // M0 실기기 시험용 — 정식 배포 전에 별도 서명 키로 바꾼다
            signingConfig = signingConfigs.getByName("debug")
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
}
