package com.dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*

class Dramabox : MainAPI() {
    override var mainUrl = "https://dramabox-beta-hazel.vercel.app"
    override var name = "Dramabox🐋"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/dramabox/foryou" to "Untukmu",
        "/api/dramabox/latest" to "Terbaru",
        "/api/dramabox/trending" to "Trending",
        "/api/dramabox/dubindo?classify=terbaru" to "Dub Indo",
    )

    private suspend fun fetchMainItems(path: String, page: Int): List<DramaItem> {
        val url = when {
            path.contains("/api/dramabox/dubindo") -> {
                val sep = if (path.contains("?")) "&" else "?"
                "$mainUrl$path${sep}page=$page"
            }
            page > 1 -> return emptyList()
            else -> "$mainUrl$path"
        }

        return tryParseJson<List<DramaItem>>(app.get(url).text)
            ?.filter { !it.bookId.isNullOrBlank() }
            .orEmpty()
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
        val items = fetchMainItems(request.data, page)
            .distinctBy { it.bookId }
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val sourcePaths = listOf(
            "/api/dramabox/foryou",
            "/api/dramabox/latest",
            "/api/dramabox/trending",
            "/api/dramabox/dubindo?classify=terbaru",
        )

        return sourcePaths
            .flatMap { path -> fetchMainItems(path, 1) }
            .distinctBy { it.bookId }
            .filter {
                it.bookName.orEmpty().contains(q, true) ||
                    it.introduction.orEmpty().contains(q, true)
            }
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?")
        val detailBody = app.get("$mainUrl/api/dramabox/detail/$bookId").text
        val episodeBody = app.get("$mainUrl/api/dramabox/allepisode/$bookId").text

        val detail = parseDetail(detailBody)
            ?: throw ErrorLoadingException("Detail tidak ditemukan")
        val chapters = parseChapters(episodeBody)
            .sortedBy { it.chapterIndex ?: Int.MAX_VALUE }

        val episodes = chapters.mapIndexed { index, chapter ->
            val number = (chapter.chapterIndex ?: index) + 1
            newEpisode(
                LoadData(
                    bookId = bookId,
                    chapterId = chapter.chapterId,
                    chapterIndex = chapter.chapterIndex
                ).toJsonData()
            ) {
                name = chapter.chapterName?.takeIf { it.isNotBlank() } ?: "EP $number"
                episode = number
                posterUrl = chapter.chapterImg
            }
        }

        val tags = detail.tags.orEmpty()

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

        val chapters = parseChapters(app.get("$mainUrl/api/dramabox/allepisode/$bookId").text)
        val chapter = chapters.firstOrNull { !parsed.chapterId.isNullOrBlank() && it.chapterId == parsed.chapterId }
            ?: chapters.firstOrNull { parsed.chapterIndex != null && it.chapterIndex == parsed.chapterIndex }
            ?: return false

        val directLinks = chapter.cdnList.orEmpty()
            .sortedByDescending { it.isDefault ?: 0 }
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
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = if (quality > 0) quality else Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
        }

        return directLinks.isNotEmpty()
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    private fun parseDetail(body: String): DramaItem? {
        return tryParseJson<DramaItem>(body)
            ?: tryParseJson<DetailWrapper>(body)?.result
            ?: tryParseJson<DetailWrapper>(body)?.data
    }

    private fun parseChapters(body: String): List<Chapter> {
        val direct = tryParseJson<List<Chapter>>(body)
        if (!direct.isNullOrEmpty()) return direct

        val wrapped = tryParseJson<EpisodeWrapper>(body)
        return wrapped?.result
            ?: wrapped?.data
            ?: wrapped?.list
            ?: wrapped?.chapterList
            ?: emptyList()
    }

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
    )

    data class DramaItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("chapterCount") val chapterCount: Int? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
    )

    data class DetailWrapper(
        @JsonProperty("result") val result: DramaItem? = null,
        @JsonProperty("data") val data: DramaItem? = null,
    )

    data class Chapter(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class EpisodeWrapper(
        @JsonProperty("result") val result: List<Chapter>? = null,
        @JsonProperty("data") val data: List<Chapter>? = null,
        @JsonProperty("list") val list: List<Chapter>? = null,
        @JsonProperty("chapterList") val chapterList: List<Chapter>? = null,
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
