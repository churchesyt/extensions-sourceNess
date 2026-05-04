package eu.kanade.tachiyomi.extension.es.tmohentai

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class TMOHentai : ConfigurableSource, ParsedHttpSource() {

    override val name = "TMOHentai"

    override val baseUrl = "https://tmohentai.app"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaSelector() = "#top_today .work-thumbnail"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        val titleLink = element.selectFirst("div.content-title a[href*='/library/']")

        title = if (titleLink != null) {
            titleLink.attr("title").ifBlank { titleLink.text().trim() }.ifBlank { "TMOHentai" }
        } else {
            element.text().ifBlank { "TMOHentai" }
        }

        thumbnail_url = element.selectFirst("img.content-thumbnail-cover")?.attr("abs:src")
            ?: titleLink?.selectFirst("img")?.attr("abs:src")
            ?: "https://via.placeholder.com/150"

        if (titleLink != null) {
            setUrlWithoutDomain(titleLink.attr("href"))
        } else {
            url = "/view_uploads/unknown"
        }

        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = "#top_weekly .work-thumbnail"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = (
            document.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("h1, h2")?.text()?.trim()?.ifBlank { null }
                ?: document.title().substringBefore("—").substringBefore("|").trim().ifBlank { null }
                ?: "TMOHentai"
            )
        url = document.baseUri().removePrefix(baseUrl).trim().ifBlank { "/" }
        thumbnail_url = (
            document.selectFirst("img.content-thumbnail-cover")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst("img[alt='cover']")?.attr("abs:src")?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: "https://via.placeholder.com/150"
            )
        description = (
            document.selectFirst("h5:containsOwn(Sinopsis)")?.parent()?.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: document.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: "Sin descripción"
            )
        status = if (document.text().contains("Status Ongoing", ignoreCase = true)) SManga.ONGOING else SManga.UNKNOWN
        val genreList = document.select("a[href*='/biblioteca?type='], a[href*='/biblioteca?tag=']").eachText().joinToString()
        if (genreList.isNotBlank()) {
            genre = genreList
        }
    }

    override fun chapterListSelector() = "a[href^='/view_uploads/']:not([href*='#page-']), a[href^='https://tmohentai.app/view_uploads/']:not([href*='#page-'])"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.text().trim().ifBlank { "Leer" }
        scanlator = "TMOHentai"

        setUrlWithoutDomain(element.attr("href"))
        // date_upload = no date in the web
    }

    // "/cascade" to get all images
    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl${chapter.url}", headers)
    }

    override fun pageListParse(document: Document): List<Page> = mutableListOf<Page>().apply {
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

    override fun imageUrlParse(document: Document): String {
        return try {
            document.selectFirst("div.reader-img-wrap img, img.reader-img, img[data-src], img[data-original]")?.let { el ->
                el.attr("abs:src").ifBlank { el.attr("abs:data-src") }.ifBlank { el.attr("abs:data-original") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        } catch (e: Exception) {
            Log.e("TMOHentai", "imageUrlParse error for ${document.baseUri()}", e)
            ""
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        url.addQueryParameter("title", query)

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is Types -> {
                    if (filter.toUriPart().isNotEmpty()) {
                        url.addQueryParameter("content", filter.toUriPart())
                    }
                }
                is GenreList -> {
                    filter.state
                        .filter { genre -> genre.state }
                        .forEach { genre -> url.addQueryParameter("tags[]", genre.id) }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/view_uploads/$id", headers)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_ID_SEARCH)

            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val readerDocument = response.asJsoup()

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
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Géneros", genres)

    override fun getFilterList() = FilterList(
        Types(),
        Filter.Separator(),
        FilterBy(),
        SortBy(),
        Filter.Separator(),
        GenreList(getGenreList()),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class Types : UriPartFilter(
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

    private class FilterBy : UriPartFilter(
        "Campo de orden",
        arrayOf(
            Pair("Nombre", "name"),
            Pair("Artista", "artist"),
            Pair("Revista", "magazine"),
            Pair("Tag", "tag"),
        ),
    )

    class SortBy : Filter.Sort(
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
            Pair("Alfabético", "alphabetic"),
            Pair("Creación", "publication_date"),
            Pair("Popularidad", "popularity"),
        )
    }
}
