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
 * Parses a manga list page into a [MangasPage].
 *
 * Title source priority (confirmed via DevTools on /rank):
 *   1. `h3` inside the card (rendered text, always available after hydration)
 *   2. `img[alt]` attribute fallback
 *
 * The "📕等待加载。。。" prefix is a server-side loading placeholder emitted by
 * Next.js before hydration; it appears in the raw text of some `<a>` tags and must
 * be stripped. We avoid this entirely by targeting `h3` or `img[alt]` specifically.
 */
internal fun parseMangaList(response: Response, baseUrl: String): MangasPage {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    val elements = document.select("a[href^=/comics/]")
    if (elements.isNotEmpty()) {
        val mangas = elements.mapNotNull { el ->
            mangaFromElement(el).takeIf { it.title.isNotBlank() }
        }.distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= 10)
    }

    return fallbackMangaListParse(body)
}

/**
 * Extracts an [SManga] from a manga card [element].
 *
 * Card DOM (confirmed via DevTools):
 * ```
 * <a href="/comics/62881" class="group relative ...">
 *   <img class="object-cover" src="https://..." />
 *   <h3 class="font-bold text-white text-sm line-clamp-2">Title</h3>
 * </a>
 * ```
 * Title: prefer `h3` inner text → fallback to `img[alt]`.
 * Cover: `img.object-cover[src]` (fully-qualified URL, no lazy-loading).
 */
internal fun mangaFromElement(element: Element): SManga = SManga.create().apply {
    url = element.attr("href")
    // h3 holds the rendered title — most reliable across all card layouts
    title = element.selectFirst("h3")?.text()?.trim()
        ?.ifBlank { element.selectFirst("img")?.attr("alt")?.trim() }
        .orEmpty()
    thumbnail_url = element.selectFirst("img.object-cover")?.attr("src")
        ?: element.selectFirst("img")?.attr("src")
}

/**
 * Regex-based last-resort fallback when JSoup finds no hydrated `<a>` tags.
 * Scans the raw HTML for `/comics/<id>` anchors and extracts title + cover.
 */
private fun fallbackMangaListParse(body: String): MangasPage {
    val anchorRegex = "<a[^>]+href=[\"'](/comics/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
    val titleRegex = "(?i)<h3[^>]*>([^<]+)</h3>".toRegex()
    val altRegex = "(?i)alt=[\"']([^\"']+)[\"']".toRegex()
    val imgRegex = "(?i)<img[^>]+src=[\"']([^\"']+)[\"']".toRegex()

    val mangas = anchorRegex.findAll(body)
        .mapNotNull { match ->
            val url = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            if (innerHtml.contains("更多") || innerHtml.contains("排行榜")) return@mapNotNull null

            val title = titleRegex.find(innerHtml)?.groupValues?.get(1)?.trim()
                ?: altRegex.find(innerHtml)?.groupValues?.get(1)?.trim()
                ?: innerHtml.replace(Regex("<[^>]+>"), "").trim()
            val img = imgRegex.find(innerHtml)?.groupValues?.get(1)

            if (url.isBlank() || title.length <= 1) return@mapNotNull null

            SManga.create().apply {
                this.url = url
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
 * Data sources (server-rendered by Next.js — reliably available):
 * - `meta[property=og:description]` — synopsis
 * - `meta[property=og:image]` — cover image
 * - `a[href*=/categories?]` — genre tags (note `?`, not `/`)
 * - `h1` — title
 * - Body text contains "连载中" or "已完结" for status
 */
internal fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
    title = document.select("h1").firstOrNull()?.text()?.trim() ?: ""
    author = document.selectFirst("h1 + *")?.text()?.trim()
        ?: document.select("meta[name=author]").attr("content").ifBlank { null }
    description = document.select("meta[property=og:description]").attr("content")
        .ifBlank { document.selectFirst(".line-clamp-3")?.text() }
    // Confirmed selector: genre links use /categories? (with query-string, not path segment)
    genre = document.select("a[href*=/categories?]").joinToString { it.text().trim() }
    val pageText = document.text()
    status = when {
        pageText.contains("连载中") -> SManga.ONGOING
        pageText.contains("已完结") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
    thumbnail_url = document.select("meta[property=og:image]").attr("content")
        .ifBlank { document.selectFirst("img.object-cover")?.attr("src") }
}

// ================================= Chapters ==================================

/**
 * Parses a list of [SChapter] from [response].
 *
 * Key findings from DevTools:
 * - Chapter links use `/read/<id>` (NOT `/chapters/`)
 * - The page initially shows only ~5 chapters; a "查看全部" / "展开全部" button
 *   controls the rest — but in the static HTML served to Tachiyomi (no JS),
 *   all chapter `<a>` tags may still be present in the DOM (just hidden by CSS).
 *   We select ALL of them regardless.
 * - The page lists chapters NEWEST FIRST (descending), which is what Tachiyomi
 *   expects — so no reversal is needed.
 * - Latest chapters have a `<span>NEW</span>` badge; strip it from the name.
 */
internal fun parseChapterList(response: Response, baseUrl: String): List<SChapter> {
    val body = response.body.string()
    val document = Jsoup.parse(body, baseUrl)

    val elements = document.select("a[href^=/read/]")
    if (elements.isNotEmpty()) {
        return elements
            .map { el -> chapterFromElement(el) }
            .distinctBy { it.url }
    }

    // Regex fallback
    val chapterRegex = "href=[\"'](/read/\\d+)[\"'][^>]*>([\\s\\S]*?)</a>".toRegex()
    return chapterRegex.findAll(body)
        .map { match ->
            SChapter.create().apply {
                url = match.groupValues[1]
                name = cleanChapterName(match.groupValues[2].replace(Regex("<[^>]+>"), ""))
            }
        }
        .distinctBy { it.url }
        .toList()
}

/** Extracts a [SChapter] from a chapter list [element]. */
private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
    url = element.attr("href")
    // Remove the NEW badge span text and clean up whitespace
    name = cleanChapterName(element.text())
}

/**
 * Strips the "NEW" badge and any surrounding whitespace from a chapter name.
 * Example input:  "NEW第60话"  →  output: "第60话"
 */
private fun cleanChapterName(raw: String): String =
    raw.replace(Regex("^NEW\\s*", RegexOption.IGNORE_CASE), "").trim()

// =================================== Pages ====================================

/**
 * Parses [Page] image URLs from a chapter reader [response].
 *
 * Strategy 1: JSoup — `<img>` tags whose src/data-src path contains `/chapter/`.
 * Strategy 2: Regex — scan Next.js `self.__next_f.push(...)` JSON payloads for
 *             image URLs (jpg/png/webp) referencing chapter images.
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
