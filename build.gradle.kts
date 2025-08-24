import java.io.RandomAccessFile

plugins {
    id("java")
    id("maven-publish")
    id("signing")
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("de.undercouch.download") version "5.6.0"
}

val mesaVersion = "25.2.1"

group = "org.glavo"
version = mesaVersion + "-SNAPSHOT"
description = "Mesa Loader for windows"

val packageName = "org.glavo.mesa"

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    (options as CoreJavadocOptions).apply {
        addBooleanOption("Xdoclint:none", true)
    }
}

tasks.compileJava {
    options.release.set(8)

    doLast {
        val tree = fileTree(destinationDirectory)
        tree.include("**/*.class")
        tree.forEach {
            RandomAccessFile(it, "rw").use { rf ->
                rf.seek(7)   // major version
                rf.write(50)   // java 6
                rf.close()
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "$packageName.Loader"
        )
    }
}

val mesaArches = listOf("x86", "x64", "arm64")
val mesaDrivers = listOf("llvmpipe", "d3d12", "zink")

val mesaDir = layout.buildDirectory.dir("download/mesa-$mesaVersion")

val downloadMesa by tasks.registering(de.undercouch.gradle.tasks.download.Download::class) {
    val urlBase = "https://github.com/mmozeiko/build-mesa/releases/download/$mesaVersion"

    for (arch in mesaArches) {
        for (driver in mesaDrivers) {
            src("$urlBase/mesa-$driver-$arch-$mesaVersion.7z")
        }
    }

    dest(mesaDir)
    overwrite(false)
}

fun which(command: String): File? {
    for (item in System.getenv("PATH")!!.split(File.pathSeparatorChar)) {
        val file = File(item, command)
        if (file.canExecute()) {
            return file
        }
    }

    return null
}


interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

val extractMesa by tasks.registering {
    dependsOn(downloadMesa)

    val injected = project.objects.newInstance<InjectedExecOps>()

    val mesaArchiveNames = mesaArches.flatMap { arch ->
        mesaDrivers.map { driver ->
            "mesa-$driver-$arch-$mesaVersion"
        }
    }

    inputs.files(mesaArchiveNames.map { name -> mesaDir.map { it.file("$name.7z") } })
    outputs.dirs(mesaArchiveNames.map { name -> mesaDir.map { it.dir(name) } })

    doLast {
        val os = org.gradle.internal.os.OperatingSystem.current()

        val sevenZ = if (os.isWindows) {
            which("7z.exe") ?: run {
                val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"

                var file = File(programFiles, "7-Zip\\7z.exe")
                if (file.canExecute())
                    return@run file

                file = File(programFiles, "7-Zip-Zstandard\\7z.exe")
                if (file.canExecute())
                    return@run file

                return@run null
            }
        } else {
            which("7zz") ?: which("7z")
        }

        if (sevenZ == null)
            throw GradleException("7z not found in PATH")

        for (name in mesaArchiveNames) {
            val archiveFile = mesaDir.map { it.file("$name.7z") }.get().asFile
            val outputDir = mesaDir.map { it.dir(name) }.get().asFile

            outputDir.deleteRecursively()

            injected.execOps.exec {
                commandLine(
                    sevenZ.absolutePath, "x", archiveFile.absolutePath, "-o${outputDir.absolutePath}", "-y"
                )
            }.assertNormalExitValue()
        }
    }
}

val versionFile = mesaDir.map { it.file("version.properties") }
val createVersionFile = tasks.register("createVersionFile") {
    outputs.file(versionFile)

    doLast {
        val file = versionFile.get().asFile

        file.parentFile.mkdirs()
        file.writeText("loader.version=${project.version}\n")
    }
}

tasks.withType<Jar> {
    dependsOn(extractMesa, createVersionFile)
    manifest {
        attributes(
            "Premain-Class" to "$packageName.Loader"
        )
    }
    into(packageName.replace('.', '/')) {
        from(versionFile)
    }
}

fun Jar.addMesaDlls(arch: String) {
    for (driver in mesaDrivers) {
        into("$packageName.$arch.$driver".replace('.', '/')) {
            val baseDir = mesaDir.map { it.dir("mesa-$driver-$arch-$mesaVersion") }

            from(baseDir.map { it.file("opengl32.dll") })
            if (driver == "d3d12") {
                from(baseDir.map { it.file("dxil.dll") })
            }
        }
    }
}

tasks.jar {
    for (arch in mesaArches) {
        addMesaDlls(arch)
    }
}

for (arch in mesaArches) {
    tasks.register<Jar>("jar-$arch") {
        tasks.build.get().dependsOn(this)
        archiveClassifier.set(arch)

        from(sourceSets["main"].runtimeClasspath)

        addMesaDlls(arch)
    }
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            version = project.version.toString()
            artifactId = project.name

            from(components["java"])

            for (arch in mesaArches) {
                artifact(tasks["jar-$arch"])
            }

            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/Glavo/mesa-loader-windows")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("glavo")
                        name.set("Glavo")
                        email.set("zjx001202@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/Glavo/mesa-loader-windows")
                }
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        rootProject.ext["signing.keyId"].toString(),
        rootProject.ext["signing.key"].toString(),
        rootProject.ext["signing.password"].toString(),
    )
    sign(publishing.publications["maven"])
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(rootProject.ext["sonatypeUsername"].toString())
            password.set(rootProject.ext["sonatypePassword"].toString())
        }
    }
}
