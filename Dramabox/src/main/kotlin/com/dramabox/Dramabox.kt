package com.dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*

class Dramabox : MainAPI() {
    override var mainUrl = "https://dramabox.sansekai.my.id"
    override var name = "Dramabox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/dramabox/latest" to "Terbaru",
        "/api/dramabox/trending" to "Trending",
        "/api/dramabox/vip" to "VIP",
        "/api/dramabox/randomdrama" to "Acak",
    )

    private suspend fun fetchMainItems(path: String): List<DramaItem> {
        val url = if (path.startsWith("http")) path else "$mainUrl$path"
        val body = app.get(url).text

        tryParseJson<List<DramaItem>>(body)?.let { return it.filter { !it.bookId.isNullOrBlank() } }
        tryParseJson<VipResponse>(body)?.columnVoList
            ?.flatMap { it.bookList.orEmpty() }
            ?.let { return it.filter { item -> !item.bookId.isNullOrBlank() } }

        return emptyList()
    }

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val id = bookId ?: return null
        val title = bookName?.trim().orEmpty()
        if (title.isBlank()) return null

        return newTvSeriesSearchResponse(
            title,
            "$mainUrl/book/$id",
            TvType.AsianDrama
        ) {
            posterUrl = coverWap
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>())
        val items = fetchMainItems(request.data)
            .distinctBy { it.bookId }
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val sourcePaths = listOf(
            "/api/dramabox/latest",
            "/api/dramabox/trending",
            "/api/dramabox/vip",
            "/api/dramabox/randomdrama",
        )

        return sourcePaths
            .flatMap { path -> fetchMainItems(path) }
            .distinctBy { it.bookId }
            .filter {
                val title = it.bookName.orEmpty()
                val intro = it.introduction.orEmpty()
                title.contains(q, true) || intro.contains(q, true)
            }
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?")
        val detail = app.get("$mainUrl/api/dramabox/detail?bookId=$bookId").parsed<DramaItem>()
        val chapters = app.get("$mainUrl/api/dramabox/allepisode?bookId=$bookId").parsed<List<Chapter>>()
            .sortedBy { it.chapterIndex ?: Int.MAX_VALUE }

        val episodes = chapters.mapIndexed { index, chapter ->
            val number = (chapter.chapterIndex ?: index) + 1
            newEpisode(
                LoadData(bookId = bookId, chapterId = chapter.chapterId, chapterIndex = chapter.chapterIndex)
                    .toJsonData()
            ) {
                name = chapter.chapterName?.takeIf { it.isNotBlank() } ?: "EP $number"
                episode = number
                posterUrl = chapter.chapterImg
            }
        }

        val tags = detail.tags.orEmpty().ifEmpty {
            detail.tagV3s.orEmpty().mapNotNull { it.tagName }
        }

        return newTvSeriesLoadResponse(
            name = detail.bookName ?: "Dramabox",
            url = url,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = detail.coverWap
            plot = detail.introduction
            this.tags = tags
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
        val chapterId = parsed.chapterId
        val chapterIndex = parsed.chapterIndex

        val chapters = app.get("$mainUrl/api/dramabox/allepisode?bookId=$bookId").parsed<List<Chapter>>()
        val chapter = chapters.firstOrNull { !chapterId.isNullOrBlank() && it.chapterId == chapterId }
            ?: chapters.firstOrNull { chapterIndex != null && it.chapterIndex == chapterIndex }
            ?: return false

        val cdnPriority = chapter.cdnList.orEmpty().sortedByDescending { it.isDefault ?: 0 }
        val directLinks = cdnPriority
            .flatMap { cdn ->
                cdn.videoPathList.orEmpty().mapNotNull { video ->
                    val videoUrl = video.videoPath?.trim().orEmpty()
                    if (videoUrl.isBlank()) return@mapNotNull null
                    Triple(cdn.cdnDomain.orEmpty(), video.quality ?: 0, videoUrl)
                }
            }
            .distinctBy { it.third }

        directLinks.forEach { (cdnDomain, quality, videoUrl) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append("Dramabox")
                        if (quality > 0) append(" ${quality}p")
                        if (cdnDomain.isNotBlank()) append(" - $cdnDomain")
                    },
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                }
            )
        }

        return directLinks.isNotEmpty()
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
    )

    data class VipResponse(
        @JsonProperty("columnVoList") val columnVoList: List<VipColumn>? = null,
    )

    data class VipColumn(
        @JsonProperty("bookList") val bookList: List<DramaItem>? = null,
    )

    data class DramaItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("chapterCount") val chapterCount: Int? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("tagV3s") val tagV3s: List<Tag>? = null,
    )

    data class Tag(
        @JsonProperty("tagName") val tagName: String? = null,
    )

    data class Chapter(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class CdnItem(
        @JsonProperty("cdnDomain") val cdnDomain: String? = null,
        @JsonProperty("isDefault") val isDefault: Int? = null,
        @JsonProperty("videoPathList") val videoPathList: List<VideoPath>? = null,
    )

    data class VideoPath(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("videoPath") val videoPath: String? = null,
    )
}
