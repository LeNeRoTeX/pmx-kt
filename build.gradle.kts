plugins {
    kotlin("jvm") version "2.1.0"
    `java-library`
    `maven-publish`
}

group = "com.lenerotex"
version = (findProperty("version") as String?)
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
    ?: "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api("com.lenerotex:pmx-format-kt:0.1.0-SNAPSHOT")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "pmx-kt",
            "Implementation-Version" to project.version,
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifactId = "pmx-kt"
            pom {
                name.set("pmx-kt")
                description.set(
                    "Kotlin/JVM port of pmx-vm — loads, decrypts, and executes encrypted .pmx bytecode.",
                )
                url.set("https://github.com/LeNeRoTeX/${githubRepoName()}")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("lenerotex")
                        name.set("LeNeRoTeX")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/LeNeRoTeX/${githubRepoName()}.git")
                    developerConnection.set("scm:git:ssh://git@github.com/LeNeRoTeX/${githubRepoName()}.git")
                    url.set("https://github.com/LeNeRoTeX/${githubRepoName()}")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            // GITHUB_REPOSITORY is set by GitHub Actions to "owner/repo"; fall back for local runs.
            val repoSlug = System.getenv("GITHUB_REPOSITORY") ?: "LeNeRoTeX/${githubRepoName()}"
            url = uri("https://maven.pkg.github.com/$repoSlug")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: (findProperty("gpr.user") as String?)
                password = System.getenv("GITHUB_TOKEN") ?: (findProperty("gpr.token") as String?)
            }
        }
    }
}

/** Repo name fallback used when GITHUB_REPOSITORY is not present (local builds). */
fun githubRepoName(): String = (findProperty("githubRepoName") as String?) ?: "pmx-kt"
