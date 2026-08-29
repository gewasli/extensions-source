import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ITSACG"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "福利漫畫"
        lang = "zh"
        baseUrl = "https://www.itsacgaa.online/"
    }
}
