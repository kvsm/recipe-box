plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.recipebox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.recipebox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // One fixed key for every build (here and on GitHub), so new builds install over old ones
    // without wiping your recipes. It's a development key: fine for installing on your own
    // phones, but make a separate, secret release key before ever putting this on the Play Store.
    signingConfigs {
        create("dev") {
            storeFile = file("signing/recipebox-dev.jks")
            storePassword = "recipebox-dev"
            keyAlias = "recipebox"
            keyPassword = "recipebox-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Fetches the two typefaces (both SIL Open Font License) from the Google Fonts
// repo into assets on first build. If it can't, the app falls back to system fonts.
val fontsDir: File = file("src/main/assets/fonts")
val downloadFonts by tasks.registering {
    val fonts = mapOf(
        "BricolageGrotesque.ttf" to
            "https://raw.githubusercontent.com/google/fonts/main/ofl/bricolagegrotesque/BricolageGrotesque%5Bopsz%2Cwdth%2Cwght%5D.ttf",
        "AtkinsonHyperlegible-Regular.ttf" to
            "https://raw.githubusercontent.com/google/fonts/main/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf",
        "AtkinsonHyperlegible-Bold.ttf" to
            "https://raw.githubusercontent.com/google/fonts/main/ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Bold.ttf",
    )
    outputs.dir(fontsDir)
    doLast {
        fontsDir.mkdirs()
        fonts.forEach { (name, url) ->
            val target = File(fontsDir, name)
            if (target.exists() && target.length() > 0) return@forEach
            runCatching {
                java.net.URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.onFailure {
                target.delete()
                logger.warn("Couldn't download $name (${it.message}). Drop it into ${fontsDir.path} by hand, or the app uses system fonts.")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadFonts) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.tink.android)
    implementation(libs.androidx.browser)
    // On-device text recognition for screenshots when there's no Claude key (bundled model, works offline).
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
}
