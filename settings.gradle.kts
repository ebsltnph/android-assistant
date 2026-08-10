pluginManagement {
    repositories {
        // 国内镜像优先（阿里云），加快依赖下载；官方仓库兜底
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // jlatexmath-android 只在 JitPack 发布（Aliyun 无 jitpack 镜像，需认证不可用）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "assistant"
include(":app")
