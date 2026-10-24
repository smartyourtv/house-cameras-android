pluginManagement {
    repositories {
        val localRepo = java.io.File("/home/hatch/workspace/build-support/local-maven-repo")
        if (localRepo.exists()) { maven { url = localRepo.toURI() } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HouseCameras"
include(":app")
