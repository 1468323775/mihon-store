import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "CopyManga"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "拷贝漫画"
        lang = "zh"
        baseUrl = "https://www.mangacopy.com"
    }

    deeplink {
        path("/comic/..*")
    }
}
