import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Version : fichier VERSION à la racine (VERSION_MAJOR, VERSION_MINOR, PATCHLEVEL).
 * versionCode = MAJOR*10000 + MINOR*100 + PATCH, toujours croissant pour qu'Android accepte la mise à jour.
 * Hors du tag vX.Y.Z (ou avec des modifs), versionName porte le commit : 0.2.0+a1b2c3d4[.dirty].
 */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val versionFields = rootProject.file("VERSION").readLines()
    .mapNotNull { Regex("""\s*(\w+)\s*=\s*(\d+)\s*""").matchEntire(it)?.destructured }
    .associate { (k, v) -> k to v.toInt() }
fun versionNumber(key: String) = versionFields[key]?.takeIf { it in 0..99 }
    ?: throw GradleException("VERSION : $key doit être un nombre de 0 à 99")
val vMajor = versionNumber("VERSION_MAJOR")
val vMinor = versionNumber("VERSION_MINOR")
val vPatch = versionNumber("PATCHLEVEL")
val releaseTag = "v$vMajor.$vMinor.$vPatch"
val gitSha = git("rev-parse", "--short=8", "HEAD")
val onTag = git("tag", "--points-at", "HEAD").orEmpty().lines().any { it == releaseTag }
val dirty = gitSha != null && git("status", "--porcelain", "--untracked-files=no") != null
val appVersionName = "$vMajor.$vMinor.$vPatch" +
    if (gitSha == null || (onTag && !dirty)) "" else "+$gitSha" + if (dirty) ".dirty" else ""
val appVersionCode = vMajor * 10_000 + vMinor * 100 + vPatch

/*
 * Clé de signature des releases, hors du dépôt : fichier properties (storeFile, storePassword,
 * keyAlias, keyPassword) dans $POELE_RELEASE_SIGNING ou ~/.config/poele/poele-release.properties,
 * créé par `make keystore`.
 */
val releaseSigning = file(
    System.getenv("POELE_RELEASE_SIGNING") ?: "${System.getProperty("user.home")}/.config/poele/poele-release.properties",
).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

val updateRepo = providers.gradleProperty("poele.updateRepo").orNull.orEmpty().trim()

android {
    namespace = "dev.ppp0.poele"
    // Compose 1.13 / Lifecycle 2.11 exigent de compiler contre 37.2 ; targetSdk reste 36.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.ppp0.poele"
        minSdk = 33
        targetSdk = 36
        versionCode = maxOf(appVersionCode, 1)
        versionName = appVersionName
        buildConfigField("String", "RELEASE_TAG", "\"$releaseTag\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // S'installe à côté de la version normale, sans conflit de signature ; ne se met jamais à jour seule.
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Poêle (dev)")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "Poêle")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    testImplementation("junit:junit:4.13.2")
    // Vrai org.json pour les tests JVM (android.jar n'a que des stubs).
    testImplementation("org.json:json:20260814")
}
