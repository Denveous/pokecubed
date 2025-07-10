plugins {
    java
}

tasks.register<JavaCompile>("compileUpdater") {
    source = fileTree("src/main/java")
    classpath = files()
    destinationDirectory.set(file("build/classes"))
}

tasks.register<Jar>("jar") {
    archiveBaseName.set("PokeCubedUpdater")
    archiveVersion.set("")
    from("build/classes")
    manifest {
        attributes("Main-Class" to "Updater")
    }
    dependsOn("compileUpdater")
}

tasks.register<Copy>("copyToBin") {
    from(tasks.jar)
    into("../bin")
    dependsOn("jar")
}

tasks.build {
    finalizedBy("copyToBin")
}
