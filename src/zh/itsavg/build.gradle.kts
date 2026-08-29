import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ITSACG"
    versionCode = 1
    versionName = "1.0"
    contentWarning = ContentWarning.NSFW
    // libVersion 不要手动写，由 catalog alias 接管，删除该行

    source {
        name = "福利漫畫"
        lang = "zh"
        baseUrl = "https://www.itsacgaa.online/"
        // 示例：JS引擎，需要同目录新建 source.js 写爬虫逻辑
        engine {
            js(file("source.js"))
        }
    }
}