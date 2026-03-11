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
import java.net.URLDecoder
import java.net.URLEncoder

class Dramabox : MainAPI() {
    override var mainUrl = buildMainUrl()
    private val apiUrl = buildApiUrl()
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

        val url = "$apiUrl/api/search?keyword=${encodeQuery(keyword)}&page=1&size=50"
        val response = tryParseJson<DramaListResponse>(app.get(url).text)
        return response?.data.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = extractDramaId(url)
        if (dramaId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val localTitle = getQueryParam(url, "title")
        val localPoster = getQueryParam(url, "poster")
        val localIntro = getQueryParam(url, "intro")
        val localTags = getQueryParam(url, "tags")
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
        val localEpisodeCount = getQueryParam(url, "ep")?.toIntOrNull()

        val needDetail = localEpisodeCount == null || localTitle.isNullOrBlank() || localPoster.isNullOrBlank() || localIntro.isNullOrBlank()
        val detail = if (needDetail) fetchDramaDetail(dramaId) else null
        val episodeCount = localEpisodeCount ?: detail?.data?.episodeCount ?: inferEpisodeCount(dramaId)
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

        val title = localTitle?.takeIf { it.isNotBlank() }
            ?: detail?.data?.title?.takeIf { it.isNotBlank() }
            ?: "DramaBox"
        val safeUrl = buildDramaUrl(dramaId)

        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = localPoster?.takeIf { it.isNotBlank() } ?: detail?.data?.coverImage
            plot = localIntro?.takeIf { it.isNotBlank() } ?: detail?.data?.introduction
            tags = localTags ?: detail?.data?.tags
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
        val prefix = if (path.startsWith("http", true)) path else "$apiUrl$path"
        val join = if (prefix.contains("?")) "&" else "?"
        val url = "$prefix${join}page=$page"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<DramaListResponse>(body)
    }

    private suspend fun fetchDramaDetail(dramaId: String): DramaDetailResponse? {
        val url = "$apiUrl/api/dramas/$dramaId"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<DramaDetailResponse>(body)
    }

    private suspend fun fetchChapterForEpisode(dramaId: String, episodeNo: Int): ChapterContent? {
        val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=$episodeNo"
        val body = runCatching { app.post(url).text }.getOrNull() ?: return null
        val response = tryParseJson<ChapterResponse>(body) ?: return null

        return (response.data.orEmpty() + response.extras.orEmpty())
            .firstOrNull { it.chapterIndex?.toIntOrNull() == episodeNo }
    }

    private suspend fun inferEpisodeCount(dramaId: String): Int {
        val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=1"
        val body = runCatching { app.post(url).text }.getOrNull() ?: return 0
        val response = tryParseJson<ChapterResponse>(body) ?: return 0

        return (response.data.orEmpty() + response.extras.orEmpty())
            .mapNotNull { it.chapterIndex?.toIntOrNull() }
            .maxOrNull()
            ?: 0
    }

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val id = getPreferredDramaId(this)
        val title = title?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(
            title,
            buildDramaUrl(
                dramaId = id,
                title = title,
                poster = coverImage,
                intro = introduction,
                tags = tags,
                episodeCount = episodeCount
            ),
            TvType.AsianDrama
        ) {
            posterUrl = coverImage
        }
    }

    private fun getPreferredDramaId(item: DramaItem): String {
        val fromCover = extractIdFromCover(item.coverImage)
        if (!fromCover.isNullOrBlank()) return fromCover
        return item.id?.trim().orEmpty()
    }

    private fun extractIdFromCover(coverImage: String?): String? {
        val clean = coverImage
            ?.substringBefore("@")
            ?.substringBefore("?")
            ?.trim()
            .orEmpty()
        if (clean.isBlank()) return null

        val tail = clean.substringAfterLast("/")
        val id = tail.substringBefore(".").trim()
        if (id.length < 6 || !id.all { it.isDigit() }) return null
        return id
    }

    private fun buildDramaUrl(
        dramaId: String,
        title: String? = null,
        poster: String? = null,
        intro: String? = null,
        tags: List<String>? = null,
        episodeCount: Int? = null,
    ): String {
        val params = mutableListOf<String>()
        title?.takeIf { it.isNotBlank() }?.let { params.add("title=${encodeQuery(it)}") }
        poster?.takeIf { it.isNotBlank() }?.let { params.add("poster=${encodeQuery(it)}") }
        intro?.takeIf { it.isNotBlank() }?.let { params.add("intro=${encodeQuery(it)}") }
        tags?.takeIf { it.isNotEmpty() }?.let { params.add("tags=${encodeQuery(it.joinToString("|"))}") }
        episodeCount?.takeIf { it > 0 }?.let { params.add("ep=$it") }
        val suffix = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return "dramabox://drama/$dramaId$suffix"
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

    private fun getQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null
        val value = query.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    private fun buildMainUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            119, 119, 119, 46, 100, 114, 97, 109, 97, 98, 111, 120, 46, 99, 111, 109,
            47, 105, 110
        )
        val sb = StringBuilder()
        for (code in codes) sb.append(code.toChar())
        return sb.toString()
    }

    private fun buildApiUrl(): String {
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
