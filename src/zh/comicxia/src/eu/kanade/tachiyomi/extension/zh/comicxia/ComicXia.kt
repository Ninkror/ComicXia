import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.floatOrNull
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
class ComicXia : HttpSource(), ConfigurableSource {

    override val name = "漫画侠"
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

    private val preferences: SharedPreferences = getPreferences()

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
        return GET("$apiBase/comics?keyword=$q&page=$page&limit=20", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.last() != "comics") {
            // It was an ID search request which routes to details endpoint
            val manga = try {
                mangaDetailsParse(response)
            } catch (e: Exception) {
                return MangasPage(emptyList(), false)
            }
            return MangasPage(listOf(manga), false)
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

        val hostPref = preferences.getString(IMAGE_HOST_PREF_KEY, IMAGE_HOST_DEFAULT)!!

        return urls.mapIndexed { i, url ->
            // Replace host dynamically based on user setting
            val modifiedUrl = try {
                val httpUrl = url.toHttpUrlOrNull()
                if (httpUrl != null) {
                    httpUrl.newBuilder().host(hostPref).build().toString()
                } else url
            } catch (e: Exception) {
                url
            }

            val finalUrl = if (key != null && iv != null) {
                "$modifiedUrl#key=$key&iv=$iv"
            } else {
                modifiedUrl
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val imageHostPref = ListPreference(screen.context).apply {
            key = IMAGE_HOST_PREF_KEY
            title = "更换图片来源(线路)"
            summary = "如果章节内的图片加载失败或缓慢，请尝试更换线路。"
            entries = arrayOf(
                "图源 1 (mwfimsvfast27.cc)",
                "图源 2 (mwappimgs.cc)",
                "图源 3 (mwfimsvfast6.cc)",
                "图源 4 移动友好 (mwfimsvfast16.cc)",
                "图源 1 备用 (mwfimsvfast17.cc)",
                "图源 4 (mwfimsvfast18.cc)",
                "图源 5 (mwfimsvfast9.cc)",
                "图源 5 备用 (mwfimsvfast20.cc)",
                "新节点 (mwfimsvfast26.cc)"
            )
            entryValues = arrayOf(
                "mwfimsvfast27.cc",
                "mwappimgs.cc",
                "mwfimsvfast6.cc",
                "mwfimsvfast16.cc",
                "mwfimsvfast17.cc",
                "mwfimsvfast18.cc",
                "mwfimsvfast9.cc",
                "mwfimsvfast20.cc",
                "mwfimsvfast26.cc"
            )
            setDefaultValue(IMAGE_HOST_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(IMAGE_HOST_PREF_KEY, selected).commit()
            }
        }
        screen.addPreference(imageHostPref)
    }

    companion object {
        const val ID_SEARCH_PREFIX = "id:"
        private const val IMAGE_HOST_PREF_KEY = "PREF_IMAGE_HOST"
        private const val IMAGE_HOST_DEFAULT = "mwappimgs.cc"
    }
}
