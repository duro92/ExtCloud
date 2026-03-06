package com.reelshort

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

class Reelshort : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = buildBaseUrl()
    override var name = "ReelShort💗"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/v1/reelshort/dramadub" to "Drama Dub",
        "/api/v1/reelshort/newrelease" to "Rilis Baru",
        "/api/v1/reelshort/recommend" to "Rekomendasi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val shelf = fetchShelfWithFallback(request.data)
        val items = shelf?.books.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val url = "$mainUrl/api/v1/reelshort/search?keywords=${encodeQuery(keyword)}"
        val response = tryParseJson<SearchWrapper>(app.get(url).text)
        return response?.results.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = extractBookId(url)
        if (bookId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val filteredFromUrl = getQueryParam(url, "filtered_title")?.takeIf { it.isNotBlank() }
        val detail = fetchBookDetail(bookId)
        val filteredTitle = filteredFromUrl
            ?: detail?.filteredTitle?.takeIf { it.isNotBlank() }

        val chapterNameById = detail?.chapterBase
            .orEmpty()
            .mapNotNull { chapter ->
                val id = chapter.chapterId ?: return@mapNotNull null
                id to chapter.chapterName
            }
            .toMap()

        val episodeItems = fetchEpisodes(bookId, filteredTitle)
            .filter { (it.episode ?: 0) > 0 }
            .distinctBy { it.episode to it.chapterId }
        val episodes = if (episodeItems.isNotEmpty()) {
            episodeItems
                .sortedBy { it.episode ?: Int.MAX_VALUE }
                .mapIndexed { index, episode ->
                    val number = episode.episode ?: index + 1
                    newEpisode(
                        LoadData(
                            bookId = bookId,
                            filteredTitle = filteredTitle,
                            episode = number,
                            chapterId = episode.chapterId
                        ).toJsonData()
                    ) {
                        name = chapterNameById[episode.chapterId] ?: "Episode $number"
                        this.episode = number
                    }
                }
        } else {
            detail?.chapterBase
                .orEmpty()
                .mapIndexed { index, chapter ->
                    val number = index + 1
                    newEpisode(
                        LoadData(
                            bookId = bookId,
                            filteredTitle = filteredTitle,
                            episode = number,
                            chapterId = chapter.chapterId
                        ).toJsonData()
                    ) {
                        name = chapter.chapterName?.takeIf { it.isNotBlank() } ?: "Episode $number"
                        this.episode = number
                    }
                }
        }

        val title = detail?.bookTitle?.takeIf { it.isNotBlank() }
            ?: "ReelShort"

        val safeUrl = buildBookUrl(bookId, filteredTitle)
        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = detail?.bookPic
            plot = detail?.specialDesc
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
        val episode = parsed.episode ?: return false
        val filteredTitle = parsed.filteredTitle
        if (filteredTitle.isNullOrBlank()) return false

        val chapterId = parsed.chapterId
            ?: fetchEpisodes(bookId, filteredTitle)
                .firstOrNull { it.episode == episode }
                ?.chapterId
            ?: return false

        val url = buildVideoUrl(bookId, episode, filteredTitle, chapterId)
        val response = tryParseJson<VideoResponse>(app.get(url).text) ?: return false
        val videoUrl = response.videoUrl?.trim().orEmpty()
        if (videoUrl.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "ReelShort",
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            }
        )

        return true
    }

    private suspend fun fetchShelf(path: String): ShelfResponse? {
        val url = if (path.startsWith("http", true)) path else "$mainUrl$path"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<ShelfResponse>(body)
    }

    private suspend fun fetchShelfWithFallback(path: String): ShelfResponse? {
        val primary = fetchShelf(path)
        if (!primary?.books.isNullOrEmpty()) return primary

        if (path.contains("/api/v1/reelshort/recommend", true)) {
            val fallback = fetchShelf("/api/v1/reelshort/newrelease")
            if (!fallback?.books.isNullOrEmpty()) return fallback
        }

        return primary
    }

    private suspend fun fetchBookDetail(bookId: String): BookItem? {
        val paths = listOf(
            "/api/v1/reelshort/dramadub",
            "/api/v1/reelshort/newrelease",
            "/api/v1/reelshort/recommend",
        )

        for (path in paths) {
            val shelf = fetchShelf(path) ?: continue
            val item = shelf.books?.firstOrNull { it.bookId == bookId }
            if (item != null) return item
        }

        return null
    }

    private suspend fun fetchEpisodes(bookId: String, filteredTitle: String?): List<EpisodeItem> {
        val url = buildEpisodesUrl(bookId, filteredTitle)
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<EpisodesResponse>(body)?.episodes.orEmpty()
    }

    private fun buildEpisodesUrl(bookId: String, filteredTitle: String?): String {
        val suffix = filteredTitle?.takeIf { it.isNotBlank() }?.let { "?filtered_title=$it" }.orEmpty()
        return "$mainUrl/api/v1/reelshort/episodes/$bookId$suffix"
    }

    private fun buildVideoUrl(bookId: String, episode: Int, filteredTitle: String, chapterId: String): String {
        return "$mainUrl/api/v1/reelshort/video/$bookId/$episode?filtered_title=$filteredTitle&chapter_id=$chapterId"
    }

    private fun BookItem.toSearchResult(): SearchResponse? {
        val id = bookId ?: return null
        val title = bookTitle?.trim().orEmpty()
        if (title.isBlank()) return null

        val filtered = filteredTitle?.trim()
        val url = buildBookUrl(id, filtered)

        return newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = bookPic
        }
    }

    private fun buildBookUrl(bookId: String, filteredTitle: String?): String {
        val suffix = filteredTitle?.takeIf { it.isNotBlank() }?.let { "?filtered_title=$it" }.orEmpty()
        return "reelshort://book/$bookId$suffix"
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun getQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null
        return query.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
    }

    private fun extractBookId(url: String): String {
        return url.substringAfter("/book/").substringBefore("?").ifBlank {
            url.substringAfter("reelshort://").substringBefore("?").substringBefore("/")
        }.ifBlank {
            url.substringAfterLast("/").substringBefore("?")
        }
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    private fun buildBaseUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            114, 101, 101, 108, 115, 104, 111, 114, 116,
            46, 100, 114, 97, 109, 97, 118, 105, 101, 119,
            46, 119, 101, 98, 46, 105, 100
        )
        val sb = StringBuilder()
        for (code in codes) sb.append(code.toChar())
        return sb.toString()
    }

    data class ShelfResponse(
        @JsonProperty("bookshelf_name") val shelfName: String? = null,
        @JsonProperty("books") val books: List<BookItem>? = null,
    )

    data class SearchWrapper(
        @JsonProperty("results") val results: List<BookItem>? = null,
    )

    data class BookItem(
        @JsonProperty("book_id") val bookId: String? = null,
        @JsonProperty("book_title") val bookTitle: String? = null,
        @JsonProperty("filtered_title") val filteredTitle: String? = null,
        @JsonProperty("book_pic") val bookPic: String? = null,
        @JsonProperty("special_desc") val specialDesc: String? = null,
        @JsonProperty("chapter_count") val chapterCount: Int? = null,
        @JsonProperty("chapter_base") val chapterBase: List<ChapterBase>? = null,
    )

    data class ChapterBase(
        @JsonProperty("chapter_id") val chapterId: String? = null,
        @JsonProperty("chapter_name") val chapterName: String? = null,
        @JsonProperty("like_count") val likeCount: Int? = null,
        @JsonProperty("publish_at") val publishAt: String? = null,
        @JsonProperty("create_time") val createTime: String? = null,
    )

    data class EpisodesResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
    )

    data class EpisodeItem(
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("chapter_id") val chapterId: String? = null,
    )

    data class VideoResponse(
        @JsonProperty("video_url") val videoUrl: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("next_episode") val nextEpisode: EpisodeItem? = null,
    )

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("filteredTitle") val filteredTitle: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
    )
}
