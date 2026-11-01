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
    }
}

rootProject.name = "SORA Studio"

include(":app")

include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:storage")
include(":core:audio")
include(":core:dsp")
include(":core:midi")
include(":core:project")

include(":feature:home")
include(":feature:studio")
include(":feature:arrangement")
include(":feature:pianoroll")
include(":feature:drums")
include(":feature:instruments")
include(":feature:recording")
include(":feature:vocal")
include(":feature:mixer")
include(":feature:effects")
include(":feature:automation")
include(":feature:sounds")
include(":feature:export")
include(":feature:settings")
