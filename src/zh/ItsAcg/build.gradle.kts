import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ITSACG"
    versionCode = 1
    libVersion = "1.6"
    contentWarning = ContentWarning.NSFW

    source {
        name = "福利漫畫"
        lang = "zh"
        id = 123
        baseUrl = "https://www.itsacgaa.online/"
        className = "ItsAcg"
    }
}
