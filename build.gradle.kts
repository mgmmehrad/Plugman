buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.8.2")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = "net.mehradmgm.plugman"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.google.inject:guice:7.0.0")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(17)
    }

    shadowJar {
        archiveClassifier.set("unshrunk")
        archiveBaseName.set("Plugman")
    }

    // ProGuard shrinks AND obfuscates the shadow jar: random class/member
    // names, no readable package structure left on disk. See proguard.pro
    // for the one exception — the Velocity plugin entry point class must
    // keep its name, since Velocity finds it by reflection.
    val proguard = register<proguard.gradle.ProGuardTask>("proguard") {
        dependsOn(shadowJar)
        doFirst {
            layout.buildDirectory.dir("proguard").get().asFile.mkdirs()
        }
        injars(shadowJar.flatMap { it.archiveFile })
        outjars(layout.buildDirectory.file("libs/Plugman-${project.version}.jar"))

        // The JDK's own runtime classes, needed for ProGuard to resolve the
        // standard library types our code touches (java.lang.reflect, etc).
        if (JavaVersion.current().isJava9Compatible) {
            libraryjars(
                mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
                "${System.getProperty("java.home")}/jmods/java.base.jmod"
            )
        } else {
            libraryjars("${System.getProperty("java.home")}/lib/rt.jar")
        }

        // velocity-api and guice are compileOnly (provided by the proxy at
        // runtime, not bundled in our jar), so ProGuard needs them on the
        // library classpath to resolve references without bundling them.
        libraryjars(configurations.compileClasspath.get())

        configuration(file("proguard.pro"))
    }

    build {
        dependsOn(proguard)
    }
}