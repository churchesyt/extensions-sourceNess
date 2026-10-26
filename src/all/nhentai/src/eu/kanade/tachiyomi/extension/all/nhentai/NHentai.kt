package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getArtists
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getGroups
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTagDescription
import eu.kanade.tachiyomi.extension.all.nhentai.NHUtils.getTags
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
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

@Source
abstract class NHentai :
    KeiSource(),
    ConfigurableSource {
    private val nhLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = rateLimit(4)

    override fun Headers.Builder.configureHeaders() = setRandomUserAgent(filterInclude = listOf("chrome"))

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private val dataRegex = Regex("""JSON\.parse\(\s*(['\"])(.*?)\1\s*\)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val hentaiSelector = "script:containsData(JSON.parse)"
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()
    private fun String.looksLikeHtml() = trimStart().startsWith("<")

    // Dominios CDN hardcodeados como fallback si el sitio no los inyecta en el HTML
    private val fallbackImageCdnUrls = listOf("i.nhentai.net", "i3.nhentai.net")
    private val fallbackThumbCdnUrls = listOf("t.nhentai.net")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        screen.addRandomUAPreference()
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a > div").text().replace("\"", "").let {
            if (displayFullTitle) it.trim() else it.shortenTitle()
        }
        thumbnail_url = element.selectFirst(".cover img")!!.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        }
    }

    private fun mangaList(document: Document) = MangasPage(
        document.select("#content .container:not(.index-popular) .gallery").map(::mangaFromElement),
        document.selectFirst("#content > section.pagination > a.next") != null,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (nhLang.isBlank()) "$baseUrl/?page=$page" else "$baseUrl/language/$nhLang/?page=$page"
        return mangaList(client.get(url).asJsoup())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (nhLang.isBlank()) {
            "$baseUrl/search/?q=\"\"&sort=popular&page=$page"
        } else {
            "$baseUrl/language/$nhLang/popular?page=$page"
        }
        return mangaList(client.get(url).asJsoup())
    }

    private fun searchMangaUrl(page: Int, query: String, filters: FilterList): String {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val nhLangSearch = if (nhLang.isBlank()) "" else "language:$nhLang "
        val advQuery = combineQuery(filterList)
        val favoriteFilter = filterList.findInstance<FavoriteFilter>()
        val offsetPage =
            filterList.findInstance<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val url = "$baseUrl/favorites/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $advQuery")
                .addQueryParameter("page", offsetPage.toString())

            return url.build().toString()
        } else {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$query $nhLangSearch$advQuery".ifBlank { "\"\"" })
                .addQueryParameter("page", offsetPage.toString())

            filterList.findInstance<SortFilter>()?.let { f ->
                url.addQueryParameter("sort", f.toUriPart())
            }

            return url.build().toString()
        }
    }

    private fun combineQuery(filters: FilterList): String = buildString {
        filters.filterIsInstance<AdvSearchEntryFilter>().forEach { filter ->
            filter.state.split(",")
                .map(String::trim)
                .filterNot(String::isBlank)
                .forEach { tag ->
                    val y = !(filter.name == "Pages" || filter.name == "Uploaded")
                    if (tag.startsWith("-")) append("-")
                    append(filter.name, ':')
                    if (y) append('"')
                    append(tag.removePrefix("-"))
                    if (y) append('"')
                    append(" ")
                }
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val id = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> null
        }
        if (id != null) {
            require(id.toLongOrNull() != null) { "Invalid NHentai manga ID: $id" }
            val document = client.get("$baseUrl/g/$id").asJsoup()
            return MangasPage(listOf(parseMangaDetails(document).apply { url = "/g/$id/" }), false)
        }

        val url = searchMangaUrl(page, query, filters)
        val document = client.get(url).asJsoup()
        if (document.select(".fa-sign-in").isNotEmpty() && document.baseUri().contains("/login/")) {
            throw Exception("Log in via WebView to view favorites")
        }
        return mangaList(document)
    }

    private fun parseMangaDetails(document: Document): SManga {
        val pageTitle = document.select("title").text()
        if (pageTitle.contains("Just a moment", ignoreCase = true) ||
            pageTitle.contains("Cloudflare", ignoreCase = true) ||
            document.select(".cf-browser-verification, #challenge-running").isNotEmpty()
        ) {
            throw Exception("Protección de Cloudflare activa. Toca el ícono del mundo (WebView) arriba, resuelve el Captcha, y vuelve a intentar.")
        }

        val data = runCatching { document.getHentaiData() }.getOrNull()
        // FIX: usar fallback si getCdnUrls retorna lista vacía
        val cdnUrl = document.getCdnUrls(thumbnail = true)
            .ifEmpty { fallbackThumbCdnUrls }
            .random()

        if (data == null) {
            return document.getHentaiDataFromHtmlFallback()
                ?: SManga.create().apply {
                    title = pageTitle.ifBlank { "NHentai" }
                    thumbnail_url = null
                    status = SManga.COMPLETED
                    description = if (pageTitle.isBlank()) null else pageTitle
                }
        }

        val resolvedTitle = when {
            displayFullTitle -> data.title.english ?: data.title.japanese ?: data.title.pretty ?: "Unknown title"
            else -> data.title.pretty ?: (data.title.english ?: data.title.japanese)?.shortenTitle() ?: "Unknown title"
        }
        val firstPageExt = data.getPageExtensions().firstOrNull() ?: "jpg"
        return SManga.create().apply {
            title = resolvedTitle
            thumbnail_url = "https://$cdnUrl/galleries/${data.media_id}/1t.$firstPageExt"
            status = SManga.COMPLETED
            artist = getArtists(data)
            author = getGroups(data) ?: getArtists(data)
            description = "Full English and Japanese titles:\n"
                .plus("${data.title.english ?: data.title.japanese ?: data.title.pretty ?: ""}\n")
                .plus(data.title.japanese ?: "")
                .plus("\n\n")
                .plus("Pages: ${data.getPageCount()}\n")
                .plus("Favorited by: ${data.num_favorites}\n")
                .plus(getTagDescription(data))
            genre = getTags(data)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val data = document.getHentaiDataOrNull()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                scanlator = data?.let(::getGroups)
                date_upload = (data?.upload_date ?: 0L) * 1000
                setUrlWithoutDomain(document.baseUri().toHttpUrl().encodedPath)
            },
        )
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
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "g") return null
        val id = url.pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        return parseMangaDetails(client.get("$baseUrl/g/$id").asJsoup()).apply { this.url = "/g/$id/" }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        // 1. Verificación de Cloudflare / Captcha
        val title = document.select("title").text()
        if (title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Cloudflare", ignoreCase = true) ||
            document.select(".cf-browser-verification, #challenge-running").isNotEmpty()
        ) {
            throw Exception("Protección de Cloudflare activa. Toca el ícono del mundo (WebView) arriba, resuelve el Captcha, y vuelve a intentar.")
        }

        // 2. Extraer datos con manejo de errores
        val data = try {
            document.getHentaiData()
        } catch (e: Exception) {
            // Si getHentaiData() falla, mostramos este error en la pantalla del usuario
            throw Exception("Error al cargar las imágenes: ${e.message}\nIntenta abrir en WebView (ícono del mundo) para revisar si la galería fue eliminada o requiere cuenta.")
        }

        val cdnUrls = document.getCdnUrls(thumbnail = false)
            .ifEmpty { fallbackImageCdnUrls }

        val pagePaths = data.getPagePaths()
        val extensions = data.getPageExtensions()

        // 3. Verificar si realmente se encontraron páginas
        if (pagePaths.isEmpty() && extensions.isEmpty()) {
            throw Exception("No se encontraron páginas en esta galería. Puede estar corrupta o el formato de NHentai cambió.")
        }

        // 4. Mapear las imágenes si todo salió bien
        if (pagePaths.isNotEmpty()) {
            return pagePaths.mapIndexed { i, path ->
                val imageUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
                    path
                } else {
                    "https://${cdnUrls.random()}/${path.removePrefix("/")}"
                }
                Page(index = i, imageUrl = imageUrl)
            }
        }

        return extensions.mapIndexed { i, extension ->
            Page(
                index = i,
                imageUrl = "https://${cdnUrls.random()}/galleries/${data.media_id}/${i + 1}.$extension",
            )
        }
    }

    private fun Document.getHentaiData(): Hentai {
        val scripts = select(hentaiSelector)
            .map { it.data() }
            .filter { "JSON.parse(" in it }

        val script = scripts.firstOrNull { "media_id" in it && "images" in it }
            ?: scripts.firstOrNull()

        if (script == null) {
            return getHentaiDataFromSvelteScript()
                ?: error("No se pudo obtener la galería. Puede ser Cloudflare, ID inválido o cambio de sitio. Intenta abrir en WebView o revisa tu conexión.")
        }

        val encodedData = dataRegex.find(script)?.groupValues?.getOrNull(2)
            ?: return getHentaiDataFromSvelteScript()
                ?: error("No se pudo extraer el JSON de la galería. El sitio puede haber cambiado o hay un bloqueo de red.")

        val normalizedData = encodedData
            .replace(Regex("\\\\u003c", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("\\\\u003e", RegexOption.IGNORE_CASE), ">")
            .replace(Regex("\\\\u0026", RegexOption.IGNORE_CASE), "&")

        if (normalizedData.looksLikeHtml() || normalizedData.contains("doctype html", ignoreCase = true)) {
            return getHentaiDataFromSvelteScript()
                ?: error("La respuesta de la galería parece ser HTML en lugar de JSON. Intenta abrir en WebView para resolver posible bloqueo o CAPTCHA.")
        }

        return runCatching { normalizedData.parseAs<Hentai>() }
            .getOrElse {
                getHentaiDataFromSvelteScript()
                    ?: throw it
            }
    }

    private fun Document.getHentaiDataFromSvelteScript(): Hentai? {
        val wrappers = select("script[type=application/json][data-url*=/api/v2/galleries/]")
            .mapNotNull { script ->
                runCatching { script.data().parseAs<SvelteFetchedResponse>() }
                    .getOrNull()
            }

        val body = wrappers.firstOrNull { it.status in 200..299 && it.body.contains("\"media_id\"") }?.body
            ?: wrappers.firstOrNull { it.status in 200..299 }?.body
            ?: return null

        if (body.looksLikeHtml()) return null

        return runCatching { body.parseAs<Hentai>() }.getOrNull()
    }

    private fun Document.getHentaiDataFromHtmlFallback(): SManga? {
        val titleText = selectFirst("#info h1 .pretty, #info h1 .english, #info h1 .japanese")?.text()
            ?: selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val artists = select("a[href^=/artist/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val groups = select("a[href^=/group/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val tags = select("a[href^=/tag/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val categories = select("a[href^=/category/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val parodies = select("a[href^=/parody/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val characters = select("a[href^=/character/]").eachText().joinToString(", ").takeIf(String::isNotBlank)
        val pagesCount = select("div.tag-container:containsOwn(Pages:)").text().substringAfter("Pages:").trim().ifBlank { null }

        val thumbnail = selectFirst("#cover img")?.let { img ->
            if (img.hasAttr("data-src")) img.attr("abs:data-src") else img.attr("abs:src")
        } ?: selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        return SManga.create().apply {
            title = titleText
            thumbnail_url = thumbnail
            status = SManga.COMPLETED
            artist = artists.orEmpty()
            author = groups ?: artists.orEmpty()
            genre = tags.orEmpty()
            description = buildString {
                categories?.let { append("Categories: ", it, "\n") }
                parodies?.let { append("Parodies: ", it, "\n") }
                characters?.let { append("Characters: ", it, "\n") }
                pagesCount?.let { append("Pages: ", it, "\n") }
            }.ifBlank { null }
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun Hentai.getPageExtensions(): List<String> {
        val fromImages = images.pages.mapNotNull { image ->
            image.extension.ifBlank { null }
        }
        if (fromImages.isNotEmpty()) return fromImages

        return pages.mapNotNull { page ->
            page.path.substringAfterLast('.', "").substringBefore('?').ifBlank { null }
        }
    }

    private fun Hentai.getPagePaths(): List<String> = pages.map { page -> page.path }

    private fun Hentai.getPageCount(): Int = if (images.pages.isNotEmpty()) images.pages.size else pages.size

    private fun Document.getHentaiDataOrNull(): Hentai? = runCatching { getHentaiData() }.getOrNull()

    private fun Document.getCdnUrls(thumbnail: Boolean): List<String> {
        val regex = Regex(
            if (thumbnail) {
                """thumb_cdn_urls:\s*(\[.*])"""
            } else {
                """image_cdn_urls:\s*(\[.*])"""
            },
        )
        val html = body().html()
        val match = regex.find(html)
        return if (match != null) {
            val cdnJson = match.groupValues[1]
            runCatching { cdnJson.parseAs<List<String>>() }.getOrDefault(
                if (thumbnail) fallbackThumbCdnUrls else fallbackImageCdnUrls,
            )
        } else {
            if (thumbnail) fallbackThumbCdnUrls else fallbackImageCdnUrls
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TagFilter(),
        CategoryFilter(),
        GroupFilter(),
        ArtistFilter(),
        ParodyFilter(),
        CharactersFilter(),
        Filter.Header("Uploaded valid units are h, d, w, m, y."),
        Filter.Header("example: (>20d)"),
        UploadedFilter(),
        Filter.Header("Filter by pages, for example: (>20)"),
        PagesFilter(),

        Filter.Separator(),
        SortFilter(),
        OffsetPageFilter(),
        Filter.Header("Sort is ignored if favorites only"),
        FavoriteFilter(),
    )

    class TagFilter : AdvSearchEntryFilter("Tags")
    class CategoryFilter : AdvSearchEntryFilter("Categories")
    class GroupFilter : AdvSearchEntryFilter("Groups")
    class ArtistFilter : AdvSearchEntryFilter("Artists")
    class ParodyFilter : AdvSearchEntryFilter("Parodies")
    class CharactersFilter : AdvSearchEntryFilter("Characters")
    class UploadedFilter : AdvSearchEntryFilter("Uploaded")
    class PagesFilter : AdvSearchEntryFilter("Pages")
    open class AdvSearchEntryFilter(name: String) : Filter.Text(name)

    class OffsetPageFilter : Filter.Text("Offset results by # pages")

    private class FavoriteFilter : Filter.CheckBox("Show favorites only", false)

    private class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popular: All Time", "popular"),
                Pair("Popular: Month", "popular-month"),
                Pair("Popular: Week", "popular-week"),
                Pair("Popular: Today", "popular-today"),
                Pair("Recent", "date"),
            ),
        )

    private inline fun <reified T> String.parseAs(): T {
        val data = Regex("""\\u([0-9A-Fa-f]{4})""").replace(this) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        if (data.trimStart().startsWith("<") || data.contains("<!doctype", ignoreCase = true)) {
            throw IllegalArgumentException("Input looks like HTML, not JSON")
        }

        return json.decodeFromString(data)
    }
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        private const val TITLE_PREF = "Display manga title as:"
    }
}
