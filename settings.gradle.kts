// settings.gradle.kts — context-pack
//
// Plugin resolution order: mavenLocal (for `publishToMavenLocal` dev builds of
// build-tools) → GitHub Packages Maven → gradle plugin portal → mavenCentral.
// Same shape as auditlogic/module.

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.pkg.github.com/zerobias-org/util")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "zerobias-org"
                password = System.getenv("READ_TOKEN")
                    ?: System.getenv("NPM_TOKEN")
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("zb.workspace") version "1.+"
        id("zb.base") version "1.+"
        id("zb.npm-only") version "1.+"
    }
}

rootProject.name = "context-pack"

// Auto-discover packs under package/. A directory is a pack if it contains
// build.gradle.kts. Project names mirror the filesystem:
// package/context-pack-dev → :context-pack-dev
val packageDir = file("package")
if (packageDir.exists()) {
    packageDir.walkTopDown()
        .filter { it.name == "build.gradle.kts" }
        .forEach { buildFile ->
            val packDir = buildFile.parentFile
            val relativePath = packDir.relativeTo(packageDir).path
            val projectPath = relativePath.replace(File.separatorChar, ':')

            include(projectPath)
            project(":$projectPath").projectDir = packDir
        }
}
