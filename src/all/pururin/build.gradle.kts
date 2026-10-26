import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Pururin"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "en", "ja").forEach { language ->
        source {
            lang = language
            baseUrl = "https://pururin.me"
        }
    }
}
