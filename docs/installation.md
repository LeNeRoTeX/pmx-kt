# Installation

`pmx-kt` is a single-jar Kotlin/JVM library published as
`com.lenerotex:pmx-kt`.

- **Group:** `com.lenerotex`
- **Artifact:** `pmx-kt`
- **Distribution:** GitHub Packages (`https://maven.pkg.github.com/LeNeRoTeX/<repo>`)
- **Toolchain:** built against JDK 17 (works on any JDK ≥ 17).

GitHub Packages always requires an authenticated request — even for public
artifacts. Generate a personal access token (classic) with the
`read:packages` scope, then store it locally.

## Personal access token

In `~/.gradle/gradle.properties` (do **not** commit this):

```properties
gpr.user=your-github-login
gpr.token=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Or as environment variables (`GITHUB_ACTOR`, `GITHUB_TOKEN`).

## Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/LeNeRoTeX/lang")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.token").get()
        }
    }
}

dependencies {
    implementation("com.lenerotex:pmx-kt:0.1.0")
}
```

## Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
    maven {
        url = "https://maven.pkg.github.com/LeNeRoTeX/lang"
        credentials {
            username = project.findProperty("gpr.user")
            password = project.findProperty("gpr.token")
        }
    }
}

dependencies {
    implementation "com.lenerotex:pmx-kt:0.1.0"
}
```

## Maven

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/LeNeRoTeX/lang</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.lenerotex</groupId>
    <artifactId>pmx-kt</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

In `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>your-github-login</username>
    <password>ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</password>
  </server>
</servers>
```

## Building from source

```bash
git clone https://github.com/LeNeRoTeX/lang.git
cd lang/pmx-kt
./gradlew build                  # compile + tests
./gradlew publishToMavenLocal    # install to ~/.m2/repository
```

After `publishToMavenLocal` you can consume the artifact from any local
project with `mavenLocal()` in its repositories block.
