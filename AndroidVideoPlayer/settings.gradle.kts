// 本机无法访问 repo.maven.apache.org / plugins.gradle.org（DNS 被污染），
// 因此统一走国内镜像：阿里云 google / central / gradle-plugin，腾讯云 maven-public 兜底。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}

rootProject.name = "AndroidVideoPlayer"
include(":app")
