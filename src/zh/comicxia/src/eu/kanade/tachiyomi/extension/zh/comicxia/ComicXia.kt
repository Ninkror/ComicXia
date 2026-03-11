package eu.kanade.tachiyomi.extension.zh.comicxia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ComicXia extension — uses the internal REST API (`/api/v1/...`).
 * The site is a Next.js SPA that ships zero comic HTML in server-rendered pages.
 *
 * Confirmed API structure (2026-03-11):
 *   GET /api/v1/comics?sort=view&page=N           → popular
 *   GET /api/v1/comics?sort=updated&page=N        → latest
 *   GET /api/v1/comics?keyword=QUERY&page=N       → search  (NOT ?q=)
 *   GET /api/v1/comics/{id}                       → manga details
 *   GET /api/v1/comics/{id}/chapters?page=N&limit=50 → chapter list (paginated, max 50/page)
 *   GET /read/{chapterId}                         → reader page (images via Next.js payload)
 */
class ComicXia : HttpSource() {

    override val name = "ComicXia"
    override val baseUrl = "https://www.comicxia.com"
    override val lang = "zh"
    override val supportsLatest = true

    private val apiBase = "$baseUrl/api/v1"

    // Chapter page size confirmed by API: returns max 50 per page regardless of `limit`
    private val chapterPageSize = 50

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        .add("Referer", baseUrl)
        .add("Accept", "application/json")

    private val json = Json { ignoreUnknownKeys = true }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request =
        GET("$apiBase/comics?sort=view&page=$page&limit=20", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaListResponse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiBase/comics?sort=updated&page=$page&limit=20", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaListResponse(response)

    // =============================== Search ===============================

    // FIX: confirmed param is `keyword=` (not `q=`).
    // `?q=` ignores the value and returns top-by-view results (same as Popular).
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$apiBase/comics?keyword=${query.trim()}&page=$page&limit=20", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        parseMangaListResponse(response)

    // =========================== Manga Details ============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.removePrefix("/comics/").trimEnd('/')
        return GET("$apiBase/comics/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        // API wraps everything under a "data" key
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val obj = root["data"]?.jsonObject ?: root  // handle both wrapped and unwrapped

        // FIX: combine category (e.g. "禁漫"), region (e.g. "日漫"), and tags array
        // into the genre field — previously only `tags` was used, losing category info.
        val categoryName = obj["category"]?.jsonPrimitive?.content
        val regionName = obj["region"]?.jsonPrimitive?.content
        val tagsList = obj["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val allGenres = listOfNotNull(categoryName, regionName) + tagsList

        return SManga.create().apply {
            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            url = "/comics/$id"
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            author = obj["author"]?.jsonPrimitive?.content
            description = obj["description"]?.jsonPrimitive?.content
            genre = allGenres.joinToString()
            status = when (obj["status"]?.jsonPrimitive?.intOrNull) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = obj["cover_original_url"]?.jsonPrimitive?.content
                ?.ifBlank { obj["cover_image"]?.jsonPrimitive?.content }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.removePrefix("/comics/").trimEnd('/')
        // FIX: API enforces max 50 items per page regardless of limit param.
        // We start at page 1; fetchAllChapters() loops through remaining pages.
        return GET("$apiBase/comics/$id/chapters?page=1&limit=$chapterPageSize", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        val total = root["total"]?.jsonPrimitive?.intOrNull ?: data.size

        // Collect first page
        val chapters = mutableListOf<SChapter>()
        chapters.addAll(data.map { chapterFromJson(it.jsonObject) })

        // Fetch remaining pages if total > one page worth of chapters
        val pageCount = (total + chapterPageSize - 1) / chapterPageSize
        if (pageCount > 1) {
            val mangaId = response.request.url.pathSegments
                .dropLast(1) // drop "chapters"
                .last()

            for (page in 2..pageCount) {
                val pageResponse = client.newCall(
                    GET("$apiBase/comics/$mangaId/chapters?page=$page&limit=$chapterPageSize", headers),
                ).execute()
                val pageRoot = json.parseToJsonElement(pageResponse.body.string()).jsonObject
                pageRoot["data"]?.jsonArray?.forEach {
                    chapters.add(chapterFromJson(it.jsonObject))
                }
            }
        }

        // API returns oldest-first (chapter_number ascending); reverse for newest-first display
        return chapters.reversed()
    }

    private fun chapterFromJson(chapter: kotlinx.serialization.json.JsonObject): SChapter =
        SChapter.create().apply {
            val chapterId = chapter["id"]?.jsonPrimitive?.content ?: ""
            url = "/read/$chapterId"
            name = chapter["title"]?.jsonPrimitive?.content
                ?.removePrefix("NEW")?.trim()
                ?: "Chapter $chapterId"
            // Prefer updated_at; fall back to created_at
            date_upload = parseDate(
                chapter["updated_at"]?.jsonPrimitive?.content
                    ?: chapter["created_at"]?.jsonPrimitive?.content,
            )
        }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val document = Jsoup.parse(body)

        // Strategy 1: img tags with chapter images
        val imgElements = document.select(
            "img[src*=/chapter/], img[data-src*=/chapter/], div.chapter-img img",
        )
        if (imgElements.isNotEmpty()) {
            return imgElements.mapIndexed { i, img ->
                Page(i, imageUrl = img.attr("data-src").ifBlank { img.attr("src") })
            }
        }

        // Strategy 2: scan Next.js RSC payload for image URLs
        val imgUrlRegex = "\"(https?://[^\"]*(?:jpg|jpeg|png|webp)[^\"]*)\"".toRegex()
        val payloadRegex = "(?s)<script[^>]*>self\\.__next_f\\.push\\((.*?)\\)</script>".toRegex()

        val urls = payloadRegex.findAll(body)
            .flatMap { payload ->
                val content = payload.groupValues[1]
                if (!content.contains(".jpg") && !content.contains(".png") && !content.contains(".webp")) {
                    return@flatMap emptySequence()
                }
                imgUrlRegex.findAll(content)
                    .map { it.groupValues[1] }
                    .filter { !it.contains("icon") && (it.contains("chapter") || it.contains("chap")) }
            }
            .distinct()
            .toList()

        return urls.mapIndexed { i, url ->
            Page(i, imageUrl = url.replace("\\\\", ""))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    // ========================= Helpers ====================================

    /**
     * Parses an ISO-8601 datetime string with optional fractional seconds and timezone offset.
     * Example input: "2025-11-29T02:36:51.910327+08:00"
     *
     * We extract the fixed-length date+time prefix (19 chars) and the last 6-char tz token,
     * then remove the colon from the tz so SimpleDateFormat "Z" pattern can parse it.
     */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank() || dateStr.length < 19) return 0L
        return try {
            // "2025-11-29T02:36:51.910327+08:00"
            //  take first 19 chars → "2025-11-29T02:36:51"
            //  take last 6 chars   → "+08:00"
            //  strip colon         → "+0800"
            val base = dateStr.substring(0, 19) // "2025-11-29T02:36:51"
            val tz = dateStr.takeLast(6).replace(":", "") // "+08:00" -> "+0800"
            dateFormat.parse("$base$tz")?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }


    private fun parseMangaListResponse(response: Response): MangasPage {
        val body = response.body.string()
        if (body.isBlank()) return MangasPage(emptyList(), false)

        val obj = json.parseToJsonElement(body).jsonObject
        val data = obj["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0

        val mangas = data.map { el ->
            val comic = el.jsonObject
            SManga.create().apply {
                val id = comic["id"]?.jsonPrimitive?.content ?: ""
                url = "/comics/$id"
                title = comic["title"]?.jsonPrimitive?.content ?: ""
                author = comic["author"]?.jsonPrimitive?.content
                thumbnail_url = comic["cover_original_url"]?.jsonPrimitive?.content
                    ?.ifBlank { comic["cover_image"]?.jsonPrimitive?.content }
                status = when (comic["status"]?.jsonPrimitive?.intOrNull) {
                    0 -> SManga.ONGOING
                    1 -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        }

        val hasNextPage = data.size >= 20 && mangas.size < total
        return MangasPage(mangas, hasNextPage)
    }
}
