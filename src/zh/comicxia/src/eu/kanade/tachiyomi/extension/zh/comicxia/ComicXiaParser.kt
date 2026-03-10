package eu.kanade.tachiyomi.extension.zh.comicxia

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// ================================= Manga List =================================

/**
 * Parses a manga list from [response].
 *
 * First tries standard JSoup CSS selectors. If no results are found (e.g. due to
 * Next.js client-side hydration not yet having run), falls back to a regex-based
 * scan of the raw HTML body.
 */
internal fun parseMangaList(response: Response, baseUrl: String): MangasPage {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    val elements = document.select("a[href^=/comics/]")
    if (elements.isNotEmpty()) {
        val mangas = elements.map { mangaFromElement(it) }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= 10)
    }

    return fallbackMangaListParse(body)
}

/** Extracts an [SManga] from a list item [element]. */
internal fun mangaFromElement(element: Element): SManga = SManga.create().apply {
    setUrlWithoutDomain(element.attr("href"))
    title = element.select("img").attr("alt").ifBlank { element.text() }.trim()
    thumbnail_url = element.select("img").attr("src")
        .ifBlank { element.select("img").attr("data-src") }
}

/**
 * Regex-based fallback for when the page HTML has not been hydrated by Next.js.
 * Scans the raw HTML string for anchor tags pointing to `/comics/<id>` paths.
 */
private fun fallbackMangaListParse(body: String): MangasPage {
    val anchorRegex = "<a[^>]+href=[\"'](/comics/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
    val titleRegex = "(?i)alt=[\"']([^\"']+)[\"']".toRegex()
    val imgRegex = "(?i)<img[^>]+src=[\"']([^\"']+)[\"']".toRegex()

    val mangas = anchorRegex.findAll(body)
        .mapNotNull { match ->
            val url = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            // Filter out navigation links by checking common non-manga text
            if (innerHtml.contains("更多") || innerHtml.contains("排行榜")) return@mapNotNull null

            val title = titleRegex.find(innerHtml)?.groupValues?.get(1)?.trim()
                ?: innerHtml.replace(Regex("<[^>]+>"), "").trim()
            val img = imgRegex.find(innerHtml)?.groupValues?.get(1)

            if (url.isBlank() || title.length <= 1) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = img
            }
        }
        .distinctBy { it.url }
        .toList()

    return MangasPage(mangas, mangas.size >= 10)
}

// ================================ Manga Details ================================

/**
 * Parses full manga details from a dedicated manga page [document].
 *
 * Uses Open Graph meta tags (`og:description`, `og:image`) which are pre-rendered
 * by Next.js server-side, making them reliably present even without JS execution.
 */
internal fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
    title = document.select("h1").firstOrNull()?.text()?.trim() ?: ""
    author = "Unknown"
    description = document.select("meta[property=og:description]").attr("content")
    genre = document.select("a[href*=/categories/]").joinToString { it.text() }
    status = if (document.text().contains("连载", ignoreCase = true)) {
        SManga.ONGOING
    } else {
        SManga.COMPLETED
    }
    thumbnail_url = document.select("meta[property=og:image]").attr("content")
}

// ================================= Chapters ==================================

/**
 * Parses a list of [SChapter] from [response].
 *
 * Tries JSoup selection first, then falls back to regex if Next.js has not
 * rendered chapter links into the static HTML.
 */
internal fun parseChapterList(response: Response, baseUrl: String): List<SChapter> {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    // Try standard DOM approach first
    val elements = document.select("a[href*=/chapters/]")
        .takeIf { it.isNotEmpty() }
        ?: document.select("a[href^=/comics/][href*=/chapters/]")

    if (elements.isNotEmpty()) {
        return elements
            .map { el ->
                SChapter.create().apply {
                    setUrlWithoutDomain(el.attr("href"))
                    name = el.text().trim()
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    // Regex fallback: match href="/comics/<id>/chapters/<id>"
    val chapterRegex = "href=[\"'](/comics/\\d+/chapters/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
    return chapterRegex.findAll(body)
        .map { match ->
            SChapter.create().apply {
                url = match.groupValues[1]
                name = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            }
        }
        .distinctBy { it.url }
        .reversed()
        .toList()
}

// =================================== Pages ====================================

/**
 * Parses [Page] image URLs from a chapter [response].
 *
 * Strategy 1: JSoup — look for `<img>` tags whose src/data-src contains `/chapter/`.
 * Strategy 2: Regex — scan inline Next.js `self.__next_f.push(...)` JSON payloads
 *             for image URLs (jpg/png/webp) that reference chapter images.
 */
internal fun parsePageList(response: Response): List<Page> {
    val body = response.body.string()
    val document = Jsoup.parse(body)

    // Strategy 1: direct img elements (works when SSR or page fully loaded)
    val imgElements = document.select(
        "img[src*=/chapter/], img[data-src*=/chapter/], div.chapter-img img",
    )
    if (imgElements.isNotEmpty()) {
        return imgElements.mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("data-src").ifBlank { img.attr("src") })
        }
    }

    // Strategy 2: extract from Next.js server component payloads
    val imgUrlRegex = "\"([^\"]*(?:jpeg|jpg|png|webp)[^\"]*)\"".toRegex()
    val payloadRegex = "(?s)<script[^>]*>self\\.__next_f\\.push\\((.*?)\\)</script>".toRegex()

    val urls = payloadRegex.findAll(body)
        .flatMap { payload ->
            val content = payload.groupValues[1]
            if (!content.contains(".jpg") && !content.contains(".png") && !content.contains(".webp")) {
                return@flatMap emptySequence()
            }
            imgUrlRegex.findAll(content)
                .map { it.groupValues[1] }
                .filter { it.contains("http") && !it.contains("icon") && it.contains("chap") }
        }
        .distinct()
        .toList()

    return urls.mapIndexed { i, url ->
        Page(i, imageUrl = url.replace("\\\\", ""))
    }
}
