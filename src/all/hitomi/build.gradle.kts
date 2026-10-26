import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hitomi"
    versionCode = 41
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf(
        "all", "en", "id", "jv", "ca", "ceb", "cs", "da", "de", "et", "es", "eo",
        "fr", "it", "hi", "hu", "pl", "pt", "vi", "tr", "ru", "uk", "ar", "ko", "zh", "ja",
    ).forEach { language ->
        source {
            lang = language
            baseUrl = "https://hitomi.la"
        }
    }
}
