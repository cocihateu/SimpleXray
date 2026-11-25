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

rootProject.name = "SimpleXray"

include(":app")

includeBuild("third_party/miuix") {
    dependencySubstitution {
        substitute(module("top.yukonga.miuix.kmp:miuix-core")).using(project(":miuix-core"))
        substitute(module("top.yukonga.miuix.kmp:miuix-ui")).using(project(":miuix-ui"))
        substitute(module("top.yukonga.miuix.kmp:miuix-preference")).using(project(":miuix-preference"))
        substitute(module("top.yukonga.miuix.kmp:miuix-material")).using(project(":miuix-material"))
        substitute(module("top.yukonga.miuix.kmp:miuix-blur")).using(project(":miuix-blur"))
        substitute(module("top.yukonga.miuix.kmp:miuix-glass")).using(project(":miuix-glass"))
        substitute(module("top.yukonga.miuix.kmp:miuix-squircle")).using(project(":miuix-squircle"))
        substitute(module("top.yukonga.miuix.kmp:miuix-icons")).using(project(":miuix-icons"))
    }
}
