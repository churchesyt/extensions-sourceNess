import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TMOHentaiApp"
    versionCode = 20
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "TMOHentai"
        lang = "es"
        baseUrl = "https://tmohentai.app"
    }

    deeplink {
        host("tmohentai.app")
        host("tmohentai.com")
        path("/contents/..*")
        path("/library/..*")
    }
}
