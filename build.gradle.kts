plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "au.com.addstar"
version = "2.0.0"

java {
    // Compiled by JDK 25 only. Gradle will not fall back to another JDK.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compile against the oldest supported server (1.20.4) so nothing newer is used by accident.
    // Runs on 1.20.4 through 26.x.
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    implementation("redis.clients:jedis:8.0.2")
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Java 21 bytecode, not 25: Paper 1.20.4 rewrites plugin classes with ASM 9.7, which cannot read
    // Java 25 class files and logs a fatal error per class. Raise this once 1.20.4 is retired.
    options.release.set(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    // SLF4J is provided by Paper; Jedis logs through the server's logger.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
        exclude(dependency("com.google.errorprone:.*"))
    }
    exclude("META-INF/maven/**", "META-INF/proguard/**", "**/module-info.class")
    val shaded = "au.com.addstar.executeeverywhere.lib"
    relocate("redis.clients", "$shaded.redis")
    relocate("org.apache.commons.pool2", "$shaded.pool2")
    relocate("org.json", "$shaded.json")
    relocate("com.google.gson", "$shaded.gson")
    manifest {
        // No NMS is used, so skip Paper's plugin remapping on 1.20.5+.
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
