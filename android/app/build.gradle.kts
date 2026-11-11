import java.io.File
import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseSigningPropertiesPath = providers.gradleProperty("tnSigningProperties")
 .orElse(providers.environmentVariable("TN_ANDROID_SIGNING_PROPERTIES"))
 .orNull
val releaseSigningPropertiesFile = releaseSigningPropertiesPath?.let { file(it).canonicalFile }
val releaseSigningProperties = Properties().apply {
 if (releaseSigningPropertiesFile?.isFile == true) releaseSigningPropertiesFile.inputStream().use(::load)
}
val releaseSigningStorePath = releaseSigningProperties.getProperty("storeFile", "")
val releaseSigningStoreType = releaseSigningProperties.getProperty("storeType", "JKS")
val releaseSigningStorePassword = releaseSigningProperties.getProperty("storePassword", "")
val releaseSigningKeyAlias = releaseSigningProperties.getProperty("keyAlias", "")
val releaseSigningKeyPassword = releaseSigningProperties.getProperty("keyPassword", "")
val releaseSigningStoreFile = releaseSigningStorePath.takeIf(String::isNotBlank)?.let { path ->
 val candidate = File(path)
 if (candidate.isAbsolute) candidate else releaseSigningPropertiesFile?.parentFile?.resolve(path)
}?.canonicalFile
val releaseSigningConfigured = releaseSigningPropertiesFile?.isFile == true &&
 releaseSigningStorePath.isNotBlank() &&
 releaseSigningStorePassword.isNotBlank() && releaseSigningKeyAlias.isNotBlank() &&
 releaseSigningKeyPassword.isNotBlank() && releaseSigningStoreFile?.isFile == true
val releaseTaskRequested = gradle.startParameter.taskNames.any { task ->
 task.substringAfterLast(':').contains("release", ignoreCase = true)
}
if (releaseTaskRequested && !releaseSigningConfigured) {
 throw GradleException(
  "Release signing is required. Set -PtnSigningProperties or TN_ANDROID_SIGNING_PROPERTIES."
 )
}

android {
 namespace="vn.tn.assistant"; compileSdk=36
        defaultConfig { applicationId="vn.tn.assistant"; minSdk=21; targetSdk=36; versionCode=26111095; versionName="26.11.10.195"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
 buildFeatures { buildConfig=true }
 signingConfigs {
  if (releaseSigningConfigured) {
   create("tnRelease") {
    storeFile = releaseSigningStoreFile
    storeType = releaseSigningStoreType
    storePassword = releaseSigningStorePassword
    keyAlias = releaseSigningKeyAlias
    keyPassword = releaseSigningKeyPassword
   }
  }
 }
 buildTypes {
  debug { buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8088\"") }
  release {
   isMinifyEnabled=false
   buildConfigField("String", "API_BASE_URL", "\"https://assistant.tnfastvn.com\"")
   if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("tnRelease")
  }
  create("preview") { initWith(getByName("debug")); applicationIdSuffix=".preview"; versionNameSuffix="-preview"; matchingFallbacks += listOf("debug"); buildConfigField("String", "API_BASE_URL", "\"https://assistant.tnfastvn.com\"") }
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 testOptions { unitTests.isIncludeAndroidResources=true }
 testBuildType=providers.gradleProperty("nativeTestBuildType").orElse("debug").get()
 packaging { jniLibs { useLegacyPackaging=true }; resources.excludes += "META-INF/DEPENDENCIES" }
 sourceSets.getByName("main") {
  java.setSrcDirs(listOf("src/independent/java"))
  manifest.srcFile("src/independent/AndroidManifest.xml")
  assets.setSrcDirs(listOf("src/independent/assets"))
  res.setSrcDirs(listOf("src/independent/res"))
  jniLibs.setSrcDirs(emptyList<String>())
 }
 sourceSets.getByName("test").java.setSrcDirs(listOf("src/independentTest/java"))
 sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/independentAndroidTest/java"))
}
dependencies { implementation("androidx.drawerlayout:drawerlayout:1.1.1"); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("org.jsoup:jsoup:1.24.1"); implementation("androidx.core:core:1.13.1"); testImplementation("junit:junit:4.13.2"); testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0"); testImplementation("org.json:json:20240303") }
// Independently obtained libraries: never load native binaries from the reference APK.
dependencies {
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test:core:1.6.1")
 implementation("androidx.constraintlayout:constraintlayout:2.2.1")
 androidTestImplementation("androidx.constraintlayout:constraintlayout:2.2.1")
 implementation("com.google.zxing:core:3.5.3")
 implementation("com.alphacephei:vosk-android:0.3.75@aar")
 implementation("net.java.dev.jna:jna:5.18.1@aar")
 implementation(files("libs/onnxruntime-android-1.20.0-tn16k.aar"))
}

dependencyLocking { lockAllConfigurations() }

// Keep Android unit tests deterministic and bounded in CI.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
 maxHeapSize="1536m"
 maxParallelForks=1
 forkEvery=40
}

