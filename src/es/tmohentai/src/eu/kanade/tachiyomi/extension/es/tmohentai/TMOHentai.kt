package eu.kanade.tachiyomi.extension.es.tmohentai

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TMOHentai :
    KeiSource(),
    ConfigurableSource {
    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 2.seconds) { it.host == baseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders() = set("Referer", "$baseUrl/")

    private val preferences: SharedPreferences by getPreferencesLazy()

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val elementHref = element.attr("href")
        val titleLink = if (element.tagName() == "a" && ("/library/" in elementHref || "/view_uploads/" in elementHref)) {
            element
        } else {
            element.selectFirst("div.content-title a[href*='/library/'], div.content-title a[href*='/view_uploads/']")
        }
            ?: element.selectFirst("a[href*='/library/'], a[href*='/view_uploads/']")

        title = if (titleLink != null) {
            titleLink.attr("title").ifBlank { titleLink.text().trim() }.ifBlank { "TMOHentai" }
        } else {
            element.selectFirst(".content-title")?.text()?.trim() ?: element.text().trim().ifBlank { "TMOHentai" }
        }

        thumbnail_url = element.selectFirst("img.content-thumbnail-cover")?.attr("abs:src")
            ?: titleLink?.selectFirst("img")?.attr("abs:src")
            ?: element.selectFirst("img")?.attr("abs:src")
            ?: "https://via.placeholder.com/150"

        if (titleLink != null) {
            setUrlWithoutDomain(titleLink.attr("href"))
        } else {
            url = "/view_uploads/unknown"
        }

        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun parseMangaList(document: Document) = MangasPage(
        document.select(".manga-card, .work-thumbnail").map(::mangaFromElement),
        document.selectFirst("a[rel=next]") != null,
    )

    override suspend fun getPopularManga(page: Int) = parseMangaList(client.get("$baseUrl/biblioteca?order_item=likes_count&order_dir=desc&page=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int) = parseMangaList(client.get("$baseUrl/biblioteca?order_item=creation&order_dir=desc&page=$page").asJsoup())

    private fun parseMangaDetails(document: Document) = SManga.create().apply {
        title = (
            document.selectFirst("#md-title")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("h1, h2")?.text()?.trim()?.ifBlank { null }
                ?: document.title().substringBefore("—").substringBefore("|").trim().ifBlank { null }
                ?: "TMOHentai"
            )
        url = document.baseUri().removePrefix(baseUrl).trim().ifBlank { "/" }
        thumbnail_url = (
            document.selectFirst("#md-cover")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst(".md-cover-card img")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst("img.content-thumbnail-cover")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst("img[alt='cover']")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: "https://via.placeholder.com/150"
            )
        description = (
            document.selectFirst(".md-info-row--synopsis .md-info-row__value")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("h5:containsOwn(Sinopsis)")?.parent()?.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: "Sin descripción"
            )
        status = if (
            document.selectFirst(".md-cover-card__status")?.text()?.contains("Ongoing", ignoreCase = true) == true ||
            document.text().contains("Status Ongoing", ignoreCase = true)
        ) {
            SManga.ONGOING
        } else {
            SManga.UNKNOWN
        }
        val genreList = document.select("#md-tags-list a, a[href*='/biblioteca?type='], a[href*='/biblioteca?tag=']")
            .eachText()
            .joinToString()
        if (genreList.isNotBlank()) {
            genre = genreList
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("a[href^='/view_uploads/']:not([href*='#page-']), a[href^='https://tmohentai.app/view_uploads/']:not([href*='#page-'])")
        .map { element ->
            SChapter.create().apply {
                name = element.text().trim().ifBlank { "Leer" }
                scanlator = "TMOHentai"
                setUrlWithoutDomain(element.attr("href"))
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

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in setOf("tmohentai.app", "tmohentai.com")) return null
        return parseMangaDetails(client.get(url).asJsoup()).apply { this.url = url.encodedPath }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return parsePageList(document)
    }

    private fun parsePageList(document: Document): List<Page> = mutableListOf<Page>().apply {
        try {
            val imgs = document.select("div.reader-img-wrap img, img.reader-img, img.lazyload, img[data-src], img[data-original]")

            if (imgs.isNotEmpty()) {
                imgs.forEachIndexed { idx, img ->
                    val el = img
                    val rawSrc = el.attr("abs:src").ifBlank { el.attr("abs:data-src") }
                        .ifBlank { el.attr("abs:data-original") }
                        .ifBlank { el.attr("abs:data-lazy") }

                    // ignore obvious placeholders
                    val finalSrc = if (rawSrc.isBlank() || rawSrc.contains("loading.gif") || rawSrc.contains("placeholder")) {
                        null
                    } else {
                        rawSrc
                    }

                    if (finalSrc != null) add(Page(idx, "", finalSrc))
                }
            }

            // fallback: try to extract image URLs embedded in scripts or meta
            if (isEmpty()) {
                val scriptText = document.select("script").joinToString("\n") { it.html() }
                val regex = Regex("https?://[^\\s'\"]+\\.(?:jpg|jpeg|png|webp)")
                val matches = regex.findAll(scriptText).map { it.value }.toList()
                if (matches.isNotEmpty()) {
                    matches.forEachIndexed { idx, url -> add(Page(idx, "", url)) }
                } else {
                    val og = document.selectFirst("meta[property=og:image]")?.attr("content")
                    if (!og.isNullOrBlank()) add(Page(0, "", og))
                }
            }
        } catch (e: Exception) {
            Log.e("TMOHentai", "pageListParse error for ${document.baseUri()}", e)
        }
    }

    private fun searchMangaUrl(page: Int, query: String, filters: FilterList): String {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("title", query)
            url.addQueryParameter("search[searchText]", query)
            url.addQueryParameter("search[searchBy]", "title")
        }

        url.addQueryParameter("page", page.toString())

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is Types -> {
                    val part = filter.toUriPart()
                    if (part.isNotEmpty() && part != "all") {
                        url.addQueryParameter("type", part)
                        url.addQueryParameter("content", part)
                    }
                }
                is GenreList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("tag[]", it.id) }
                }
                is FilterBy -> {
                    url.addQueryParameter("search_by", filter.toUriPart())
                }
                is SortBy -> {
                    if (filter.state != null) {
                        url.addQueryParameter("order_by", SORTABLES[filter.state!!.index].second)
                        url.addQueryParameter("order_dir", if (filter.state!!.ascending) "asc" else "desc")
                    }
                }
                else -> {}
            }
        }

        return url.build().toString()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = if (query.startsWith(PREFIX_ID_SEARCH)) {
        val realQuery = query.removePrefix(PREFIX_ID_SEARCH)
        require(realQuery.toLongOrNull() != null) { "Invalid TMOHentai manga ID: $realQuery" }
        val readerDocument = client.get("$baseUrl/view_uploads/$realQuery").asJsoup()

        // Extract info link and use its path as URL
        val infoLink = readerDocument.selectFirst("a[href*='/library/']")?.attr("href") ?: "/view_uploads/$realQuery"

        val manga = SManga.create().apply {
            title = (
                readerDocument.selectFirst("h1, h2, h3")?.text()?.trim()
                    ?: readerDocument.title().substringBefore("—").substringBefore("|").trim()
                ).ifBlank { "TMOHentai" }
            url = infoLink.ifBlank { "/view_uploads/$realQuery" }
            thumbnail_url = (
                readerDocument.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
                ).ifBlank { "https://via.placeholder.com/150" }
            description = "Sin descripción"
            status = SManga.UNKNOWN
        }

        MangasPage(listOf(manga), false)
    } else {
        parseMangaList(client.get(searchMangaUrl(page, query, filters)).asJsoup())
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Géneros", genres)

    override fun getFilterList(data: JsonElement?) = FilterList(
        Types(),
        Filter.Separator(),
        FilterBy(),
        SortBy(),
        Filter.Separator(),
        GenreList(getGenreList()),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class Types :
        UriPartFilter(
            "Filtrar por tipo",
            arrayOf(
                Pair("Ver todos", "all"),
                Pair("Manga", "hentai"),
                Pair("Light Hentai", "light-hentai"),
                Pair("Doujinshi", "doujinshi"),
                Pair("One-shot", "one-shot"),
                Pair("Other", "otro"),
            ),
        )

    private class FilterBy :
        UriPartFilter(
            "Campo de orden",
            arrayOf(
                Pair("Nombre", "title"),
                Pair("Artista", "artist"),
                Pair("Revista", "magazine"),
                Pair("Tag", "tag"),
            ),
        )

    class SortBy :
        Filter.Sort(
            "Ordenar por",
            SORTABLES.map { it.first }.toTypedArray(),
            Selection(2, false),
        )

    /**
     * Last check: 13/02/2023
     * https://tmohentai.app/section/hentai
     *
     * Array.from(document.querySelectorAll('#advancedSearch .list-group .list-group-item'))
     * .map(a => `Genre("${a.querySelector('span').innerText.replace(' ', '')}", "${a.querySelector('input').value}")`).join(',\n')
     */
    private fun getGenreList() = listOf(
        Genre("Big Boobs", "28"),
        Genre("Ahegao", "7"),
        Genre("Creampie", "38"),
        Genre("Big Ass", "27"),
        Genre("Blowjob", "8"),
        Genre("Milf", "2"),
        Genre("Student", "58"),
        Genre("Impregnation", "3"),
        Genre("Sole Female", "25"),
        Genre("Incest", "12"),
        Genre("Netorare", "4"),
        Genre("Colour", "31"),
        Genre("Group", "26"),
        Genre("Sole Male", "24"),
        Genre("Anal", "16"),
        Genre("Domination", "30"),
        Genre("Bukkake", "52"),
        Genre("Kissing", "43"),
        Genre("Harem", "13"),
        Genre("Nympho", "40"),
        Genre("Mature", "10"),
        Genre("Pregnant", "33"),
        Genre("Romance", "64"),
        Genre("Loli", "57"),
        Genre("Mother", "54"),
        Genre("Cheating", "9"),
        Genre("Uncensored", "63"),
        Genre("Orgy", "48"),
        Genre("Shota", "37"),
        Genre("Rape", "21"),
        Genre("Exhibitionism", "34"),
        Genre("Dark Skin", "29"),
        Genre("Fetish", "61"),
        Genre("Bbw", "23"),
        Genre("Forced", "39"),
        Genre("Virgin", "69"),
        Genre("Gyaru", "45"),
        Genre("Small Boobs", "67"),
        Genre("Deepthroat", "42"),
        Genre("Ffm Threesome", "46"),
        Genre("Double Penetration", "49"),
        Genre("Humiliation", "36"),
        Genre("Oyakodon", "53"),
        Genre("Toys", "41"),
        Genre("Fantasy", "50"),
        Genre("Femdom", "59"),
        Genre("Monsters", "35"),
        Genre("Yuri", "17"),
        Genre("Bisexual", "72"),
        Genre("Bondage", "60"),
        Genre("Mmf Threesome", "47"),
        Genre("Futanari", "44"),
        Genre("Tall Girl", "56"),
        Genre("Filming", "65"),
        Genre("Furry", "32"),
        Genre("Tomboy", "55"),
        Genre("Comedy", "71"),
        Genre("Netorase", "73"),
        Genre("Bestiality", "51"),
        Genre("Tsundere", "74"),
        Genre("Yaoi", "18"),
    )

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val pageMethodPref = androidx.preference.ListPreference(screen.context).apply {
            key = PAGE_METHOD_PREF
            title = PAGE_METHOD_PREF_TITLE
            entries = arrayOf("Cascada", "Páginado")
            entryValues = arrayOf("cascade", "paginated")
            summary = PAGE_METHOD_PREF_SUMMARY
            setDefaultValue(PAGE_METHOD_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(PAGE_METHOD_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(pageMethodPref)
    }

    private fun getPageMethodPref() = preferences.getString(PAGE_METHOD_PREF, PAGE_METHOD_PREF_DEFAULT_VALUE)

    companion object {
        private const val PAGE_METHOD_PREF = "pageMethodPref"
        private const val PAGE_METHOD_PREF_TITLE = "Método de descarga de imágenes"
        private const val PAGE_METHOD_PREF_SUMMARY = "Puede corregir errores al cargar las imágenes.\nConfiguración actual: %s"
        private const val PAGE_METHOD_PREF_CASCADE = "cascade"
        private const val PAGE_METHOD_PREF_DEFAULT_VALUE = PAGE_METHOD_PREF_CASCADE

        const val PREFIX_CONTENTS = "contents"
        const val PREFIX_ID_SEARCH = "id:"

        private val SORTABLES = listOf(
            Pair("Alfabético", "title"),
            Pair("Creación", "publication_date"),
            Pair("Popularidad", "views"),
        )
    }
}
