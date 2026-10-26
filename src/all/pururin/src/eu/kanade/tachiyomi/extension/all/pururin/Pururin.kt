package eu.kanade.tachiyomi.extension.all.pururin

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

@Source
abstract class Pururin : KeiSource() {
    private val searchLang: Pair<String, String>?
        get() = when (lang) {
            "en" -> Pair("13010", "english")
            "ja" -> Pair("13011", "japanese")
            else -> null
        }

    private val langPath: String
        get() = when (lang) {
            "en" -> "/tags/language/13010/english"
            "ja" -> "/tags/language/13011/japanese"
            else -> ""
        }

    private val json: Json by injectLazy()

    // Popular
    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.attr("title")
        setUrlWithoutDomain(element.attr("abs:href"))
        thumbnail_url = element.select("img").attr("abs:src")
    }

    private fun parseMangaList(document: Document) = MangasPage(
        document.select("a.card").map(::mangaFromElement),
        document.selectFirst(".page-item [rel=next]") != null,
    )

    override suspend fun getPopularManga(page: Int) = parseMangaList(client.get("$baseUrl/browse$langPath?sort=most-popular&page=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int) = parseMangaList(client.get("$baseUrl/browse$langPath?page=$page").asJsoup())

    private fun searchUrl(page: Int, query: String, includeTags: List<Pair<String, String>>, excludeTags: List<Pair<String, String>>, sortBy: String, pagesMin: Int, pagesMax: Int): String {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (query.isEmpty() && includeTags.size == 1 && excludeTags.isEmpty()) {
            url.addPathSegment("browse")
                .addPathSegment("tags")
                .addPathSegment("content")
                .addPathSegment(includeTags[0].first)
        } else {
            url.addPathSegment("search")
                .addQueryParameter("q", query)
                .apply {
                    if (includeTags.isNotEmpty()) addQueryParameter("included_tags", includeTags.toValue())
                    if (excludeTags.isNotEmpty()) addQueryParameter("excluded_tags", excludeTags.toValue())
                }
        }
        url.addQueryParameter("sort", sortBy)
            .addQueryParameter("start_page", pagesMin.toString())
            .addQueryParameter("last_page", pagesMax.toString())
        if (page > 1) url.addQueryParameter("page", page.toString())
        return url.build().toString()
    }

    // Search

    private fun List<Pair<String, String>>.toValue(): String = "[${this.joinToString(",") { "{\"id\":${it.first},\"name\":\"${it.second}\"}" }}]"

    private fun parsePageRange(query: String, minPages: Int = 1, maxPages: Int = 9999): Pair<Int, Int> {
        val num = query.filter(Char::isDigit).toIntOrNull() ?: -1
        fun limitedNum(number: Int = num): Int = number.coerceIn(minPages, maxPages)

        if (num < 0) return minPages to maxPages
        return when (query.firstOrNull()) {
            '<' -> 1 to if (query[1] == '=') limitedNum() else limitedNum(num + 1)
            '>' -> limitedNum(if (query[1] == '=') num else num + 1) to maxPages
            '=' -> when (query[1]) {
                '>' -> limitedNum() to maxPages
                '<' -> 1 to limitedNum(maxPages)
                else -> limitedNum() to limitedNum()
            }
            else -> limitedNum() to limitedNum()
        }
    }

    @Serializable
    class Tag(
        val id: Int,
        val name: String,
    )

    private fun findTagByNameSubstring(tags: List<Tag>, substring: String): Pair<String, String>? {
        val tag = tags.find { it.name.contains(substring, ignoreCase = true) }
        return tag?.let { Pair(tag.id.toString(), tag.name) }
    }

    private suspend fun tagSearch(tag: String, type: String): Pair<String, String>? {
        val requestBody = FormBody.Builder().add("text", tag).build()
        return client.post("$baseUrl/api/get/tags/search", requestBody).use { response ->
            findTagByNameSubstring(response.parseAs<List<Tag>>(), type)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val includeTags = mutableListOf<Pair<String, String>>()
        val excludeTags = mutableListOf<Pair<String, String>>()
        var pagesMin = 1
        var pagesMax = 9999
        var sortBy = "newest"

        searchLang?.let(includeTags::add)

        filters.forEach {
            when (it) {
                is SelectFilter -> sortBy = it.getValue()

                is TypeFilter -> {
                    val (_, inactiveFilters) = it.state.partition { stIt -> stIt.state }
                    excludeTags += inactiveFilters.map { fil -> Pair(fil.value, "${fil.name} [Category]") }
                }

                is PageFilter -> {
                    if (it.state.isNotEmpty()) {
                        val (min, max) = parsePageRange(it.state)
                        pagesMin = min
                        pagesMax = max
                    }
                }

                is TextFilter -> {
                    if (it.state.isNotEmpty()) {
                        it.state.split(",").filter(String::isNotBlank).map { tag ->
                            val trimmed = tag.trim()
                            if (trimmed.startsWith('-')) {
                                tagSearch(trimmed.lowercase().removePrefix("-"), it.type)?.let { tagInfo ->
                                    excludeTags.add(tagInfo)
                                }
                            } else {
                                tagSearch(trimmed.lowercase(), it.type)?.let { tagInfo ->
                                    includeTags.add(tagInfo)
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        if (query.isEmpty() && excludeTags.size == 1 && includeTags.isEmpty()) {
            excludeTags.addAll(excludeTags)
        }

        return parseMangaList(client.get(searchUrl(page, query, includeTags, excludeTags, sortBy, pagesMin, pagesMax)).asJsoup())
    }

    // Details

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        document.select(".box-gallery").let { e ->
            initialized = true
            title = e.select(".title").text()
            author = e.select("a[href*=/circle/]").eachText().joinToString().ifEmpty { e.select("[itemprop=author]").text() }
            artist = e.select("[itemprop=author]").eachText().joinToString()
            genre = e.select("a[href*=/content/]").eachText().joinToString()
            description = e.select(".box-gallery .table-info tr")
                .filter { tr ->
                    tr.select("td").let { td ->
                        td.isNotEmpty() &&
                            td.none { it.text().contains("content", ignoreCase = true) || it.text().contains("ratings", ignoreCase = true) }
                    }
                }
                .joinToString("\n") { tr ->
                    tr.select("td").let { td ->
                        var a = td.select("a").toList()
                        if (a.isEmpty()) a = td.drop(1)
                        td.first()!!.text() + ": " + a.joinToString { it.text() }
                    }
                }
            status = SManga.COMPLETED
            thumbnail_url = e.select("img").attr("abs:src")
        }
    }

    // Chapters

    private fun parseChapterList(document: Document): List<SChapter> {
        val result = document.select(".table-collection tbody tr a")
            .map { element ->
                SChapter.create().apply {
                    name = element.text()
                    setUrlWithoutDomain(element.attr("abs:href"))
                }
            }
            .reversed()
        return result.ifEmpty {
            listOf(
                SChapter.create().apply {
                    setUrlWithoutDomain(document.baseUri())
                    name = document.selectFirst(".box-gallery .title")?.text()?.trim().orEmpty().ifBlank { "Chapter" }
                },
            )
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document).apply { url = manga.url } else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val document = client.get(url).asJsoup()
        if (document.selectFirst(".box-gallery") == null) return null
        return parseMangaDetails(document).apply { this.url = url.encodedPath }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select(".gallery-preview a img")
            .mapIndexed { i, img ->
                Page(i, "", (if (img.hasAttr("abs:src")) img.attr("abs:src") else img.attr("abs:data-src")).replace("t.", "."))
            }
    }

    private inline fun <reified T> okhttp3.Response.parseAs(): T = json.decodeFromString(body.string())

    override fun getFilterList(data: JsonElement?) = getFilters()
}
