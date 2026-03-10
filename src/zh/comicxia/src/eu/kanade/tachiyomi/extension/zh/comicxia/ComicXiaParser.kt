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
 * The site uses Next.js App Router (SPA). When the server-side-rendered HTML is
 * available (typical Tachiyomi request), JSoup can pick up hydrated `<a>` tags
 * pointing to `/comics/<id>`. Falls back to a regex scan when hydration is absent.
 */
internal fun parseMangaList(response: Response, baseUrl: String): MangasPage {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    // Confirmed selector from live DOM inspection: all manga cards are anchors to /comics/<id>
    val elements = document.select("a[href^=/comics/]")
    if (elements.isNotEmpty()) {
        val mangas = elements.map { mangaFromElement(it) }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= 10)
    }

    return fallbackMangaListParse(body)
}

/**
 * Extracts an [SManga] from a manga card [element].
 *
 * Card layout (confirmed via DevTools):
 * - Thumbnail: `img.object-cover` inside the anchor; src is a fully-qualified URL.
 * - Title: img[alt] attribute.
 */
internal fun mangaFromElement(element: Element): SManga = SManga.create().apply {
    setUrlWithoutDomain(element.attr("href"))
    // img alt is the most reliable title source across both grid-card and list-item layouts
    title = element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        .ifBlank { element.text().trim() }
    thumbnail_url = element.selectFirst("img.object-cover")?.attr("src")
        ?: element.selectFirst("img")?.attr("src")
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
 * Priority of data sources (confirmed via DevTools):
 * 1. JSON-LD `<script type="application/ld+json">` — most reliable, server-rendered.
 * 2. Open Graph meta tags (`og:description`, `og:image`) — also server-rendered.
 * 3. Inline DOM elements as fallback.
 *
 * Genre links use `a[href*="/categories?"]` (note the `?` before query params).
 * Status is detected by presence of the text "连载中" (ongoing) or "已完结" (completed).
 */
internal fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
    title = document.select("h1").firstOrNull()?.text()?.trim() ?: ""

    // Author sits adjacent to the title; no dedicated class but confirmed by DevTools inspection
    author = document.selectFirst("h1 + *")?.text()?.trim()
        ?: document.select("meta[name=author]").attr("content").ifBlank { null }

    // og:description is pre-rendered by Next.js; most robust description source
    description = document.select("meta[property=og:description]").attr("content")
        .ifBlank { document.selectFirst(".line-clamp-3")?.text() }

    // Confirmed selector from DevTools: genre tags link to /categories? (with query string)
    genre = document.select("a[href*=/categories?]").joinToString { it.text().trim() }

    val pageText = document.text()
    status = when {
        pageText.contains("连载中") -> SManga.ONGOING
        pageText.contains("已完结") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // og:image is server-rendered — preferred over DOM img for reliability
    thumbnail_url = document.select("meta[property=og:image]").attr("content")
        .ifBlank { document.selectFirst("img.object-cover")?.attr("src") }
}

// ================================= Chapters ==================================

/**
 * Parses a list of [SChapter] from [response].
 *
 * IMPORTANT: Chapter links use `/read/<id>` format (confirmed via DevTools),
 * NOT `/chapters/` as originally assumed.
 */
internal fun parseChapterList(response: Response, baseUrl: String): List<SChapter> {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    // Confirmed selector: chapter links use /read/<id> prefix
    val elements = document.select("a[href^=/read/]")
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

    // Regex fallback for un-hydrated HTML
    val chapterRegex = "href=[\"'](/read/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
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
 * Parses [Page] image URLs from a chapter reader [response].
 *
 * Strategy 1: JSoup — look for `<img>` tags whose src contains `/chapter/`.
 * Strategy 2: Regex — scan inline Next.js `self.__next_f.push(...)` JSON payloads
 *             for image URLs (jpg/png/webp) that reference `/chapter/`.
 */
internal fun parsePageList(response: Response): List<Page> {
    val body = response.body.string()
    val document = Jsoup.parse(body)

    val imgElements = document.select(
        "img[src*=/chapter/], img[data-src*=/chapter/], div.chapter-img img",
    )
    if (imgElements.isNotEmpty()) {
        return imgElements.mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("data-src").ifBlank { img.attr("src") })
        }
    }

    // Next.js server component payload fallback
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
