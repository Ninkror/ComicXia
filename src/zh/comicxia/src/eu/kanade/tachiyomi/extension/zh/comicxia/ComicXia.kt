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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ComicXia : ParsedHttpSource() {

    override val name = "漫画侠"
    override val baseUrl = "https://www.comicxia.com"
    override val lang = "zh"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response, baseUrl)

    override fun popularMangaSelector() = "a[href^=/comics/]"

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun popularMangaNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/categories?sort=updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response, baseUrl)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?keyword=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response, baseUrl)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(document: Document): SManga = parseMangaDetails(document)

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> = parseChapterList(response, baseUrl)

    override fun chapterListSelector() = error("Not used")

    override fun chapterFromElement(element: Element): SChapter = error("Not used")

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> = parsePageList(response)

    override fun pageListParse(document: Document): List<Page> = error("Not used")

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used.")
}
