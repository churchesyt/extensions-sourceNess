import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

dependencies {
    implementation(project(":lib:unpacker"))
}

keiyoushi {
    name = "LectorHentai"
    theme = "mangathemesia"
    versionCode = 0
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://lectorhentai.com"
        lang = "es"
    }
}
