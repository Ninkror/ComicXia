package eu.kanade.tachiyomi.extension.zh.comicxia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.serialization.json.floatOrNull
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    override val client = network.client.newBuilder()
        .addInterceptor(::imageIntercept)
        .build()

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        val fragment = request.url.fragment
        if (fragment != null && fragment.contains("key=") && fragment.contains("iv=")) {
            val key = fragment.substringAfter("key=").substringBefore("&")
            val iv = fragment.substringAfter("iv=").substringBefore("&")
            
            if (key.isNotEmpty() && iv.isNotEmpty() && response.isSuccessful) {
                try {
                    val encrypted = response.body.bytes()
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val keySpec = SecretKeySpec(key.toByteArray(), "AES")
                    val ivSpec = IvParameterSpec(iv.toByteArray())
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    val decrypted = cipher.doFinal(encrypted)
                    
                    val newBody = decrypted.toResponseBody(response.body.contentType())
                    return response.newBuilder().body(newBody).build()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return response
    }

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

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        if (q.startsWith(ID_SEARCH_PREFIX)) {
            val id = q.removePrefix(ID_SEARCH_PREFIX)
            return GET("$apiBase/comics/$id", headers)
        }
        
        var isFilterSearch = false
        var categoryId = ""
        var region = ""
        var status = ""
        var tagId = ""
        var sort = "updated"

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> categoryId = filter.selected
                is RegionFilter -> region = filter.selected
                is StatusFilter -> status = filter.selected
                is SortFilter -> sort = filter.selected
                is TagFilter -> tagId = filter.selected
                else -> {}
            }
        }

        if (categoryId.isNotEmpty() || region.isNotEmpty() || status.isNotEmpty() || tagId.isNotEmpty() || sort != "updated") {
            isFilterSearch = true
        }

        if (isFilterSearch && q.isEmpty()) {
            val url = "$baseUrl/categories".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("page", page.toString())
            if (categoryId.isNotEmpty()) url.addQueryParameter("category_id", categoryId)
            if (region.isNotEmpty()) url.addQueryParameter("region", region)
            if (status.isNotEmpty()) url.addQueryParameter("status", status)
            if (tagId.isNotEmpty()) url.addQueryParameter("tag_id", tagId)
            url.addQueryParameter("sort", sort)
            
            return GET(url.toString(), headers)
        }

        // Default keyword API search
        return GET("$apiBase/comics?keyword=$q&page=$page&limit=20", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        
        if (response.request.url.pathSegments.last() != "comics" && !url.contains("/categories")) {
            // It was an ID search request which routes to details endpoint
            val manga = try {
                mangaDetailsParse(response)
            } catch (e: Exception) {
                return MangasPage(emptyList(), false)
            }
            return MangasPage(listOf(manga), false)
        }
        
        // If it's the categories HTML page
        if (url.contains("/categories")) {
            val document = Jsoup.parse(response.body.string())
            val mangas = document.select("a[href^=/comics/]").mapNotNull { el ->
                val link = el.attr("href")
                if (link == "/comics/") return@mapNotNull null
                SManga.create().apply {
                    this.url = link
                    title = el.select("p.truncate, h2, span, h3").first()?.text() ?: "Unknown"
                    thumbnail_url = el.select("img").attr("src").ifBlank { el.select("img").attr("data-src") }
                }
            }
            // Check if pagination has a next button or just check size
            val hasNextPage = mangas.size >= 24 // Next.js list seems to have larger pages
            
            return MangasPage(mangas, hasNextPage)
        }
        
        return parseMangaListResponse(response)
    }

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
            chapter_number = chapter["chapter_number"]?.jsonPrimitive?.floatOrNull ?: -1f
            // Prefer updated_at; fall back to created_at
            date_upload = parseDate(
                chapter["updated_at"]?.jsonPrimitive?.content
                    ?: chapter["created_at"]?.jsonPrimitive?.content,
            )
        }

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

        // Strategy 2: Extract Next.js payload AES setup and image URLs
        val cleanBody = body.replace("\\\"", "\"").replace("\\/", "/")
        
        val keyMatch = Regex(""""key":"([^"]+)"""").find(cleanBody)
        val ivMatch = Regex(""""iv":"([^"]+)"""").find(cleanBody)
        
        val key = keyMatch?.groupValues?.get(1)
        val iv = ivMatch?.groupValues?.get(1)

        val urls = Regex(""""original_url":"([^"]+)"""").findAll(cleanBody)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        return urls.mapIndexed { i, url ->
            val finalUrl = if (key != null && iv != null) {
                "$url#key=$key&iv=$iv"
            } else {
                url
            }
            Page(i, imageUrl = finalUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    // ========================= Helpers ====================================

    /**
     * Parses a full ISO-8601 datetime string with timezone.
     * Example: "2025-11-29T02:36:51.910327+08:00"
     *
     * Step 1: normalize to "2025-11-29T02:36:51+0800"
     *   - take first 19 chars (drop sub-seconds)
     *   - take last 6 chars of original, remove colon (+08:00 → +0800)
     * Step 2: parse with "yyyy-MM-dd'T'HH:mm:ssZ"
     */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    @Synchronized
    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank() || dateStr.length < 19) return 0L
        return try {
            val datePart = dateStr.substring(0, 19)       // "2025-11-29T02:36:51"
            val tzPart = dateStr.takeLast(6).replace(":", "") // "+08:00" → "+0800"
            dateFormat.parse("$datePart$tzPart")?.time ?: 0L
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

    // =============================== Filters ==============================

    override fun getFilterList() = FilterList(
        CategoryFilter(),
        RegionFilter(),
        StatusFilter(),
        SortFilter(),
        TagFilter()
    )

    private open class UriPartFilter(
        name: String,
        val vals: Array<Pair<String, String>>
    ) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        val selected: String
            get() = vals[state].second
    }

    private class CategoryFilter : UriPartFilter("分类", arrayOf(
        Pair("全部", ""),
        Pair("女性向", "6"),
        Pair("一般", "1"),
        Pair("BL", "2"),
        Pair("GL", "3"),
        Pair("禁漫", "4"),
        Pair("其他", "5"),
    ))

    private class RegionFilter : UriPartFilter("地区", arrayOf(
        Pair("全部", ""),
        Pair("日本", "1"),
        Pair("韩国", "2"),
        Pair("中国", "3"),
        Pair("欧美", "4"),
        Pair("其他", "5")
    ))

    private class StatusFilter : UriPartFilter("状态", arrayOf(
        Pair("全部", ""),
        Pair("连载中", "1"),
        Pair("已完结", "2")
    ))

    private class SortFilter : UriPartFilter("排序", arrayOf(
        Pair("最新更新", "updated"),
        Pair("最多点击", "view"),
        Pair("最新发布", "created")
    ))

    private class TagFilter : UriPartFilter("热门标签 (仅列出部分)", arrayOf(
        Pair("全部", ""),
        Pair("NTR", "563"),
        Pair("纯爱", "430"),
        Pair("大胸", "211"),
        Pair("后宫", "1480"),
        Pair("丝袜", "1401"),
        Pair("人妻", "340"),
        Pair("乱伦", "177"),
        Pair("姐弟", "581"),
        Pair("催眠", "1893"),
        Pair("老师", "1925"),
        Pair("百合", "2435"),
        Pair("伪娘", "916")
    ))

    companion object {
        const val ID_SEARCH_PREFIX = "id:"
    }
}
