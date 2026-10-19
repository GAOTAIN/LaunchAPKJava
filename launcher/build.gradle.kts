import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.launchapk.java.launcher"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val compileJvmSmoke by tasks.registering(JavaCompile::class) {
    source(fileTree("src/jvmSmoke/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("jvmSmoke/classes"))
    options.release.set(8)
    options.encoding = "UTF-8"
}
val jvmSmokeJar by tasks.registering(Jar::class) {
    dependsOn(compileJvmSmoke)
    from(compileJvmSmoke.flatMap { it.destinationDirectory })
    archiveFileName.set("launchapk-smoke.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/jvmSmokeAssets/jvm"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/jvmSmokeAssets"))
tasks.named("preBuild").configure { dependsOn(jvmSmokeJar) }
tasks.withType<Test>().configureEach {
    dependsOn(jvmSmokeJar)
    systemProperty("launchapk.smokeJar", jvmSmokeJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
dependencies {
    implementation(project(":nativebridge"))
    testImplementation("junit:junit:4.13.2")
}
