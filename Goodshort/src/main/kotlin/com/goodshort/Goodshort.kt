package com.goodshort

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class Goodshort : MainAPI() {
    override var mainUrl = buildBaseUrl()
    override var name = "GoodShort🍂"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "562" to "GoodShort"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetchHome(request.data, page)
            .distinctBy { it.bookId }
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        return fetchHome("562", 1)
            .distinctBy { it.bookId }
            .filter { it.bookName.orEmpty().contains(keyword, true) }
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = extractBookId(url)
        if (bookId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val detail = fetchBook(bookId)
        val chapters = fetchChapters(bookId)

        val episodes = chapters
            .distinctBy { it.id }
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapIndexed { idx, chapter ->
                val number = chapter.chapterName?.toIntOrNull() ?: (chapter.index?.plus(1)) ?: (idx + 1)
                newEpisode(
                    LoadData(
                        bookId = bookId,
                        chapterId = chapter.id,
                        episodeNo = number
                    ).toJsonData()
                ) {
                    name = chapter.chapterName?.takeIf { it.isNotBlank() } ?: "Episode $number"
                    this.episode = number
                    posterUrl = chapter.image
                }
            }

        val title = detail?.book?.bookName?.takeIf { it.isNotBlank() } ?: "GoodShort"
        val safeUrl = buildBookUrl(bookId)

        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = detail?.book?.cover
            plot = detail?.book?.introduction
            tags = detail?.book?.labels
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val bookId = parsed.bookId ?: return false
        val chapterId = parsed.chapterId ?: return false

        val play = fetchPlay(bookId, chapterId) ?: return false
        val baseUrl = play.m3u8?.trim().orEmpty()
        val keyData = play.keyData?.trim().orEmpty()
        if (baseUrl.isBlank() || keyData.isBlank()) return false

        val playlistUrl = buildVideoUrl(baseUrl, keyData)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "GoodShort",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            }
        )

        return true
    }

    private suspend fun fetchHome(channelId: String, page: Int): List<HomeItem> {
        val safePage = if (page < 1) 1 else page
        val url = "$mainUrl/api/home?channelId=$channelId&page=$safePage&pageSize=20"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<HomeResponse>(body)?.data.orEmpty()
    }

    private suspend fun fetchBook(bookId: String): BookResponse? {
        val url = "$mainUrl/api/book?id=$bookId"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<BookResponse>(body)
    }

    private suspend fun fetchChapters(bookId: String): List<ChapterItem> {
        val url = "$mainUrl/api/chapters?id=$bookId"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<ChaptersResponse>(body)?.data?.list.orEmpty()
    }

    private suspend fun fetchPlay(bookId: String, chapterId: Long): PlayResponse? {
        val url = "$mainUrl/api/play?bookId=$bookId&chapterId=$chapterId"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<PlayResponse>(body)
    }

    private fun buildVideoUrl(baseUrl: String, keyData: String): String {
        val encodedUrl = encodeQuery(baseUrl)
        val encodedKey = encodeQuery(keyData)
        return "$mainUrl/api/video?url=$encodedUrl&keyData=$encodedKey"
    }

    private fun HomeItem.toSearchResult(): SearchResponse? {
        val id = bookId?.trim().orEmpty()
        val title = bookName?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, buildBookUrl(id), TvType.AsianDrama) {
            posterUrl = cover
        }
    }

    private fun buildBookUrl(bookId: String): String {
        return "goodshort://book/$bookId"
    }

    private fun extractBookId(url: String): String {
        return url.substringAfter("book/").substringBefore("?").ifBlank {
            url.substringAfter("goodshort://").substringBefore("?").substringBefore("/")
        }.ifBlank {
            url.substringAfterLast("/").substringBefore("?")
        }
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    private fun buildBaseUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            103, 111, 111, 100, 115, 104, 111, 114, 116,
            45, 115, 116, 114, 101, 97, 109, 105, 110, 103,
            46, 118, 101, 114, 99, 101, 108, 46, 97, 112, 112
        )
        val sb = StringBuilder()
        for (code in codes) sb.append(code.toChar())
        return sb.toString()
    }

    data class HomeResponse(
        @JsonProperty("data") val data: List<HomeItem>? = null,
    )

    data class HomeItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("labels") val labels: List<String>? = null,
    )

    data class BookResponse(
        @JsonProperty("data") val data: BookData? = null,
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("success") val success: Boolean? = null,
    ) {
        val book: BookInfo?
            get() = data?.book
    }

    data class BookData(
        @JsonProperty("book") val book: BookInfo? = null,
    )

    data class BookInfo(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("labels") val labels: List<String>? = null,
        @JsonProperty("chapterCount") val chapterCount: Int? = null,
    )

    data class ChaptersResponse(
        @JsonProperty("data") val data: ChaptersData? = null,
    )

    data class ChaptersData(
        @JsonProperty("list") val list: List<ChapterItem>? = null,
    )

    data class ChapterItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("index") val index: Int? = null,
        @JsonProperty("image") val image: String? = null,
    )

    data class PlayResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("keyData") val keyData: String? = null,
    )

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("chapterId") val chapterId: Long? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
    )
}
