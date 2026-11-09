package eu.kanade.tachiyomi.extension.es.lectorhentai

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.aesDecrypt
import keiyoushi.utils.aesGcm
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class LectorHentai : MangaThemesia() {

    override val supportsFilterFetching = false

    override fun getFilterList(data: JsonElement?) = FilterList()

    override fun searchMangaUrl(page: Int, query: String) = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegments("tipo/all")
        .apply {
            if (query.isNotBlank()) addQueryParameter("s", query)
            if (page > 1) addQueryParameter("page", page.toString())
        }

    override fun searchMangaUrl(page: Int, query: String, filters: FilterList) = searchMangaUrl(page, query)

    override fun chapterListParse(document: Document): List<SChapter> {
        val readerLink = document.selectFirst("a.leer[href], #chapterlist a[href*='/read/']") ?: return emptyList()

        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(readerLink.attr("href"))
                name = readerLink.text().ifBlank { "Galería" }
            },
        )
    }

    override fun pageListParse(document: Document): List<Page> {
        val packedScript = document.select("script")
            .firstOrNull { it.data().contains(".split('|'),0,{}))") }
            ?.data()
            ?: error("Reader payload script not found")

        val unpackedScript = Unpacker.unpack(packedScript)
        val payloadJson = unpackedScript
            .substringAfter("ReaderBlob.start(", "")
            .substringBefore(")")
        check(payloadJson.isNotEmpty()) { "Reader payload not found" }

        return payloadJson.parseAs<ReaderPayload>()
            .imageUrls()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }
}

@Serializable
private class ReaderPayload(
    private val k: String,
    private val iv: String,
    private val c: String,
) {
    fun imageUrls(): List<String> {
        val plaintext = Base64.decode(c, Base64.DEFAULT)
            .aesDecrypt(
                aesGcm(
                    key = Base64.decode(k, Base64.DEFAULT),
                    iv = Base64.decode(iv, Base64.DEFAULT),
                ),
            )
            .decodeToString()

        return plaintext.parseAs<ReaderPageList>().imageUrls()
    }
}

@Serializable
private class ReaderPageList(
    @SerialName("p") private val pageUrls: List<String>,
) {
    fun imageUrls() = pageUrls
}
