plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly("com.google.crypto.tink:tink:1.24.0")
    testImplementation("com.google.crypto.tink:tink:1.24.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
}

tasks.test { useJUnitPlatform() }

tasks.test { useJUnitPlatform { excludeTags("large") } }

tasks.register<Test>("largeFileTest") {
    description =
        "Streams a >4 GiB synthetic file under a 128 MiB JVM heap and verifies 64-bit seeks."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("large") }
    maxHeapSize = "128m"
    val fixtures = layout.buildDirectory.dir("large-fixtures")
    doFirst { fixtures.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", fixtures.get().asFile.absolutePath)
    testLogging { events("passed", "failed", "standardOut") }
}
