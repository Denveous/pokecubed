plugins {
    kotlin("jvm") version "2.1.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "com.morenoland"

val versionRegex = """const val INSTALLER_VERSION = "([^"]+)"""".toRegex()
val mainKtFile = file("src/Main.kt")
val versionMatch = versionRegex.find(mainKtFile.readText())
val appVersion = versionMatch?.groupValues?.get(1) ?: "1.0"
version = appVersion

println("Detected version: $appVersion")

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.slf4j:slf4j-nop:2.0.16")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}



sourceSets {
    main {
        kotlin {
            srcDirs("src")
        }
        resources {
            srcDirs("src/resources")
        }
    }
    create("updater") {
        java {
            srcDirs("updater/src/main/java")
        }
    }
}

application {
    mainClass.set("MainKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveFileName.set("PokeCubedInstaller-${appVersion}.jar")
}

tasks.register<Copy>("copyJarToBin") {
    from(tasks.shadowJar)
    into("bin")
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    finalizedBy("copyJarToBin")
}

val compileUpdater = tasks.register<JavaCompile>("compileUpdater") {
    source = fileTree("updater/src/main/java")
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("updater-classes"))
    options.release.set(11)
}

tasks.register<Jar>("updaterJar") {
    archiveBaseName.set("PokeCubedUpdater")
    archiveVersion.set("")
    archiveClassifier.set("")
    from(compileUpdater.get().destinationDirectory)
    manifest {
        attributes("Main-Class" to "Updater")
    }
    dependsOn(compileUpdater)
}

tasks.register<Copy>("copyUpdaterToBin") {
    from(tasks.named("updaterJar"))
    into("bin")
    dependsOn("updaterJar")
}

tasks.register<Copy>("copyGenericJarToBin") {
    from(tasks.shadowJar)
    into("bin")
    rename { "PokeCubedInstaller.jar" }
    dependsOn(tasks.shadowJar)
}

tasks.build {
    finalizedBy("copyJarToBin", "copyUpdaterToBin")
}