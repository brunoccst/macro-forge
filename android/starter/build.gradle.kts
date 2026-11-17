plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The starter app: the core (../core) with a made-up game's profile (starter/game.yaml) and a
// tiny game plugin (starter.logic, shipped in the logic pack with the core's logic). Copy it to
// start an app for your own game.
val gameYaml: File = rootProject.file(providers.gradleProperty("macroforge.game").get())
fun gameKey(key: String): String = Regex("""(?m)^$key:\s*(\S+)""").find(gameYaml.readText())?.groupValues?.get(1) ?: ""
val core = project(":core")
evaluationDependsOn(":core")

android {
    namespace = "starter"
    compileSdk = 34

    defaultConfig {
        applicationId = gameKey("application_id").ifEmpty { throw GradleException("game.yaml has no application_id:") }
        minSdk = 26
        targetSdk = 34
        versionName = core.extra["macroforge.versionName"] as String
        versionCode = core.extra["macroforge.versionCode"] as Int
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-module-name", "starter")
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
