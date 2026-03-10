package eu.kanade.tachiyomi.extension.zh.comicxia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class ComicXia : ParsedHttpSource() {

    override val name = "漫画侠"
    override val baseUrl = "https://www.comicxia.com"
    override val lang = "zh"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank?page=$page", headers)

    // We override parse because Next.js might hydrate manga info inside `<script>`
    // or standard DOM elements. We first try JSoup selector.
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val document = Jsoup.parse(body, baseUrl)

        val elements = document.select(popularMangaSelector())
        if (elements.isNotEmpty()) {
            val mangas = elements.map { popularMangaFromElement(it) }.distinctBy { it.url }
            return MangasPage(mangas, mangas.size >= 10)
        }

        // Fallback to Regex extraction if JSoup misses purely JS-rendered tags
        return fallbackMangaListParse(body)
    }

    override fun popularMangaSelector() = "a[href^=/comics/]"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.select("img").attr("alt").ifBlank { element.text() }.trim()
            thumbnail_url = element.select("img").attr("src").ifBlank { element.select("img").attr("data-src") }
        }
    }

    override fun popularMangaNextPageSelector() = "TODO: Not used since we override parse"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/categories?sort=updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = error("Not used")

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?keyword=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = error("Not used")

    private fun fallbackMangaListParse(body: String): MangasPage {
        val regex = "<a[^>]+href=[\"'](/comics/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
        val matchResult = regex.findAll(body)

        val mangas = mutableListOf<SManga>()
        for (match in matchResult) {
            val url = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            // Skip non-manga links or empty ones
            if (innerHtml.contains("更多") || innerHtml.contains("排行榜")) continue

            val titleRegex = "(?i)alt=[\"']([^\"']+)[\"']".toRegex()
            val imgRegex = "(?i)<img[^>]+src=[\"']([^\"']+)[\"']".toRegex()

            val title = titleRegex.find(innerHtml)?.groupValues?.get(1)?.trim()
                ?: innerHtml.replace(Regex("<[^>]+>"), "").trim()
            val img = imgRegex.find(innerHtml)?.groupValues?.get(1)

            if (url.isNotBlank() && title.isNotBlank() && title.length > 1) { // Filter out random noise
                mangas.add(
                    SManga.create().apply {
                        setUrlWithoutDomain(url)
                        this.title = title
                        this.thumbnail_url = img
                    },
                )
            }
        }

        val uniqueMangas = mangas.distinctBy { it.url }
        return MangasPage(uniqueMangas, uniqueMangas.size >= 10)
    }

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1").firstOrNull()?.text()?.trim() ?: ""
            author = "Unknown" // Can refine using more specific selectors later
            description = document.select("meta[property=og:description]").attr("content")
            genre = document.select("a[href*=/categories/]").joinToString { it.text() }
            status = if (document.text().contains("连载", ignoreCase = true)) SManga.ONGOING else SManga.COMPLETED
            thumbnail_url = document.select("meta[property=og:image]").attr("content")
        }
    }

    // ============================== Chapters ==============================
    // Note: If SPA doesn't render chapters in standard HTML, we will fallback to extracting JS data

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val document = Jsoup.parse(body, baseUrl)

        val chapters = mutableListOf<SChapter>()

        // 1. Try standard JSoup selection
        var elements = document.select("a[href*=/chapters/]")
        if (elements.isEmpty()) {
            elements = document.select("a[href^=/comics/][href*=/chapters/]")
        }

        if (elements.isNotEmpty()) {
            chapters.addAll(elements.map { chapterFromElement(it) })
        } else {
            // 2. Fallback to Regex for chunks like `href="/comics/1220/chapters/1"`
            val chapterRegex = "href=[\"'](/comics/\\d+/chapters/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
            val matchResults = chapterRegex.findAll(body)

            for (match in matchResults) {
                chapters.add(
                    SChapter.create().apply {
                        url = match.groupValues[1]
                        name = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    },
                )
            }
        }

        return chapters.distinctBy { it.url }.reversed()
    }

    override fun chapterListSelector() = error("Not used")

    override fun chapterFromElement(element: Element): SChapter = error("Not used")

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val document = Jsoup.parse(body, baseUrl)

        // NextJS often prerenders images with specific loading patterns
        val imgElements = document.select("img[src*=/chapter/], img[data-src*=/chapter/], div.chapter-img img")

        if (imgElements.isNotEmpty()) {
            return imgElements.mapIndexed { i, img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                Page(i, imageUrl = src)
            }
        }

        // Fallback: search for array of image urls in JSON payload from Next.js
        val imgRegex = "\"([^\"]*(?:jpeg|jpg|png|webp)[^\"]*)\"".toRegex()
        val jsonPayloads = "(?s)<script[^>]*>self\\.__next_f\\.push\\((.*?)\\)</script>".toRegex().findAll(body)

        val urlList = mutableListOf<String>()
        for (payload in jsonPayloads) {
            val content = payload.groupValues[1]
            if (content.contains(".jpg") || content.contains(".png") || content.contains(".webp")) {
                val urls = imgRegex.findAll(content).map { it.groupValues[1] }
                    .filter { it.contains("http") && !it.contains("icon") && it.contains("chap") }
                    .toList()
                if (urls.isNotEmpty()) urlList.addAll(urls)
            }
        }

        return urlList.distinct().mapIndexed { i, url ->
            Page(i, imageUrl = url.replace("\\\\", ""))
        }
    }

    override fun pageListParse(document: Document): List<Page> = error("Not used")

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used.")
}
