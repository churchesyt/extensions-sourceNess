package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class Hentai(
    var id: Int = 0,
    val images: Images = Images(),
    val pages: List<ApiPage> = emptyList(),
    val media_id: String = "",
    val tags: List<Tag> = emptyList(),
    val title: Title = Title(),
    val upload_date: Long = 0,
    val num_favorites: Long = 0,
)

@Serializable
class Title(
    var english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class Images(
    val pages: List<Image> = emptyList(),
)

@Serializable
class ApiPage(
    val path: String,
)

@Serializable
class Image(
    private val t: String? = null,
    val path: String? = null,
) {
    val extension get() =
        path?.substringAfterLast('.')?.substringBefore('?')?.ifBlank { null }
            ?: when (t) {
                "w" -> "webp"
                "p" -> "png"
                "g" -> "gif"
                else -> "jpg"
            }
}

@Serializable
class Tag(
    val name: String,
    val type: String,
)

@Serializable
class SvelteFetchedResponse(
    val status: Int,
    val body: String,
)
