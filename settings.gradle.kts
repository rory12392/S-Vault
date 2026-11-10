// 共用熱點：只在 dev 的核心任務修改（CONVENTIONS §9）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // desktop 的 JDK 25 工具鏈找不到時自動下載（STACK §2）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "s-vault"

include("core", "desktop", "android")
