pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // v150: libadb-android (the monitor's ADB to the camera phone) is published on JitPack only
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") } }
    }
}

rootProject.name = "mantra-manual-camera"
include(":app")

// v113: the sister app, the NDI monitor that drives this camera
include(":monitor")
