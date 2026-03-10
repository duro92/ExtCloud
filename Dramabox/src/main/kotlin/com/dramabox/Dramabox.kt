package com.dramabox

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

class Dramabox : MainAPI() {
    override var mainUrl = buildBaseUrl()
    override var name = "DramaBox👌"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/dramas/indo" to "Drama Dub Indo",
        "/api/dramas/trending" to "Trending",
        "/api/dramas/must-sees" to "Must Sees",
        "/api/dramas/hidden-gems" to "Hidden Gems",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = if (page < 1) 1 else page
        val response = fetchDramaList(request.data, safePage)
        val items = response?.data.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasMore = response?.meta?.pagination?.hasMore ?: items.isNotEmpty()
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasMore
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val url = "$mainUrl/api/search?keyword=${encodeQuery(keyword)}&page=1&size=50"
        val response = tryParseJson<DramaListResponse>(app.get(url).text)
        return response?.data.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = extractDramaId(url)
        if (dramaId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val detail = fetchDramaDetail(dramaId)
        val episodeCount = detail?.data?.episodeCount ?: inferEpisodeCount(dramaId)
        if (episodeCount <= 0) throw ErrorLoadingException("Episode tidak ditemukan")

        val episodes = (1..episodeCount).map { episodeNo ->
            newEpisode(
                LoadData(
                    bookId = dramaId,
                    episodeNo = episodeNo
                ).toJsonData()
            ) {
                name = "Episode $episodeNo"
                episode = episodeNo
            }
        }

        val title = detail?.data?.title?.takeIf { it.isNotBlank() } ?: "DramaBox"
        val safeUrl = buildDramaUrl(dramaId)

        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = detail?.data?.coverImage
            plot = detail?.data?.introduction
            tags = detail?.data?.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val dramaId = parsed.bookId ?: return false
        val episodeNo = parsed.episodeNo ?: return false

        val chapter = fetchChapterForEpisode(dramaId, episodeNo) ?: return false
        val streams = chapter.streamUrl
            .orEmpty()
            .mapNotNull { stream ->
                val streamUrl = stream.url?.trim().orEmpty()
                if (streamUrl.isBlank()) return@mapNotNull null
                stream.copy(url = streamUrl)
            }
            .distinctBy { it.url }
            .sortedByDescending { it.quality ?: 0 }

        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            val qualityValue = stream.quality ?: Qualities.Unknown.value
            val qualityLabel = stream.quality?.let { "${it}p" } ?: "Auto"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "DramaBox $qualityLabel",
                    url = stream.url!!,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityValue
                    this.referer = "$mainUrl/"
                }
            )
        }

        return true
    }

    private suspend fun fetchDramaList(path: String, page: Int): DramaListResponse? {
        val prefix = if (path.startsWith("http", true)) path else "$mainUrl$path"
        val join = if (prefix.contains("?")) "&" else "?"
        val url = "$prefix${join}page=$page"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<DramaListResponse>(body)
    }

    private suspend fun fetchDramaDetail(dramaId: String): DramaDetailResponse? {
        val url = "$mainUrl/api/dramas/$dramaId"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<DramaDetailResponse>(body)
    }

    private suspend fun fetchChapterForEpisode(dramaId: String, episodeNo: Int): ChapterContent? {
        val url = "$mainUrl/api/chapters/video?book_id=$dramaId&episode=$episodeNo"
        val body = runCatching { app.post(url).text }.getOrNull() ?: return null
        val response = tryParseJson<ChapterResponse>(body) ?: return null

        return (response.data.orEmpty() + response.extras.orEmpty())
            .firstOrNull { it.chapterIndex?.toIntOrNull() == episodeNo }
    }

    private suspend fun inferEpisodeCount(dramaId: String): Int {
        val url = "$mainUrl/api/chapters/video?book_id=$dramaId&episode=1"
        val body = runCatching { app.post(url).text }.getOrNull() ?: return 0
        val response = tryParseJson<ChapterResponse>(body) ?: return 0

        return (response.data.orEmpty() + response.extras.orEmpty())
            .mapNotNull { it.chapterIndex?.toIntOrNull() }
            .maxOrNull()
            ?: 0
    }

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val id = id?.trim().orEmpty()
        val title = title?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, buildDramaUrl(id), TvType.AsianDrama) {
            posterUrl = coverImage
        }
    }

    private fun buildDramaUrl(dramaId: String): String {
        return "dramabox://drama/$dramaId"
    }

    private fun extractDramaId(url: String): String {
        return url.substringAfter("drama/").substringBefore("?").ifBlank {
            url.substringAfter("dramabox://").substringBefore("?").substringBefore("/")
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
            100, 98, 46, 104, 97, 102, 105, 122, 104, 105, 98, 110, 117, 115, 121, 97, 109,
            46, 109, 121, 46, 105, 100
        )
        val sb = StringBuilder()
        for (code in codes) sb.append(code.toChar())
        return sb.toString()
    }

    data class DramaListResponse(
        @JsonProperty("data") val data: List<DramaItem>? = null,
        @JsonProperty("meta") val meta: ResponseMeta? = null,
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class DramaDetailResponse(
        @JsonProperty("data") val data: DramaItem? = null,
        @JsonProperty("meta") val meta: ResponseMeta? = null,
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class DramaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover_image") val coverImage: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    data class ResponseMeta(
        @JsonProperty("pagination") val pagination: Pagination? = null,
    )

    data class Pagination(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("size") val size: Int? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("has_more") val hasMore: Boolean? = null,
    )

    data class ChapterResponse(
        @JsonProperty("data") val data: List<ChapterContent>? = null,
        @JsonProperty("extras") val extras: List<ChapterContent>? = null,
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("message") val message: String? = null,
    )

    data class ChapterContent(
        @JsonProperty("chapter_index") val chapterIndex: String? = null,
        @JsonProperty("stream_url") val streamUrl: List<StreamItem>? = null,
    )

    data class StreamItem(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
    )
}
