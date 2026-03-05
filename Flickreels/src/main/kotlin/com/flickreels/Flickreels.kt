package com.flickreels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Flickreels : MainAPI() {
    override var mainUrl = buildBaseUrl()
    override var name = "FlickReels🍂"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "foryou" to "Untukmu",
        "rank" to "Peringkat"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "rank" -> if (page == 1) fetchRank() else emptyList()
            else -> fetchForyou(page)
        }

        val results = items
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = request.data != "rank" && items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, results), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val sources = mutableListOf<ShowItem>()
        sources += fetchForyou(1)
        sources += fetchForyou(2)
        sources += fetchRank()

        return sources
            .distinctBy { it.id }
            .filter { it.title.orEmpty().contains(keyword, true) }
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val playletId = extractPlayletId(url)
        if (playletId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val detail = fetchChapters(playletId)
            ?: throw ErrorLoadingException("Detail tidak ditemukan")

        val episodes = detail.list.orEmpty()
            .sortedBy { it.chapterNum ?: Int.MAX_VALUE }
            .mapIndexed { index, chapter ->
                val number = chapter.chapterNum ?: index + 1
                val isLocked = chapter.isLock == 1 || chapter.hlsUrl.isNullOrBlank()
                val baseName = chapter.chapterTitle?.takeIf { it.isNotBlank() } ?: "Episode $number"
                val displayName = if (isLocked) "$baseName (Locked)" else baseName

                newEpisode(
                    LoadData(
                        playletId = playletId,
                        chapterId = chapter.chapterId,
                        hlsUrl = chapter.hlsUrl,
                        episode = number
                    ).toJsonData()
                ) {
                    name = displayName
                    this.episode = number
                    posterUrl = chapter.chapterCover
                }
            }

        val title = detail.title?.takeIf { it.isNotBlank() } ?: "FlickReels"
        val safeUrl = buildPlayletUrl(playletId)

        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = detail.cover
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val hlsUrl = parsed.hlsUrl?.trim().orEmpty()
        if (hlsUrl.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "FlickReels",
                url = hlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            }
        )

        return true
    }

    private suspend fun fetchForyou(page: Int): List<ShowItem> {
        if (page <= 0) return emptyList()
        val url = "$mainUrl/api/foryou?page=$page&lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<ShowListResponse>(body)?.data.orEmpty()
    }

    private suspend fun fetchRank(): List<ShowItem> {
        val url = "$mainUrl/api/rank?lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<ShowListResponse>(body)?.data.orEmpty()
    }

    private suspend fun fetchChapters(playletId: String): ChaptersData? {
        val url = "$mainUrl/api/chapters?id=$playletId&lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<ChaptersResponse>(body)?.data
    }

    private fun ShowItem.toSearchResult(): SearchResponse? {
        val id = id?.trim().orEmpty()
        val title = title?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null

        return newTvSeriesSearchResponse(title, buildPlayletUrl(id), TvType.AsianDrama) {
            posterUrl = cover
        }
    }

    private fun buildPlayletUrl(playletId: String): String {
        return "flickreels://playlet/$playletId"
    }

    private fun extractPlayletId(url: String): String {
        return url.substringAfter("playlet/").substringBefore("?").ifBlank {
            url.substringAfter("flickreels://").substringBefore("?").substringBefore("/")
        }.ifBlank {
            url.substringAfterLast("/").substringBefore("?")
        }
    }

    private fun LoadData.toJsonData(): String = this.toJson()

    private fun buildBaseUrl(): String {
        val codes = intArrayOf(
            104, 116, 116, 112, 115, 58, 47, 47,
            102, 108, 105, 99, 107, 114, 101, 101, 108, 115,
            45, 115, 116, 114, 101, 97, 109, 105, 110, 103,
            46, 118, 101, 114, 99, 101, 108, 46, 97, 112, 112
        )
        val sb = StringBuilder()
        for (code in codes) sb.append(code.toChar())
        return sb.toString()
    }

    data class ShowListResponse(
        @JsonProperty("data") val data: List<ShowItem>? = null,
    )

    data class ShowItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class ChaptersResponse(
        @JsonProperty("status_code") val statusCode: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("data") val data: ChaptersData? = null,
    )

    data class ChaptersData(
        @JsonProperty("playlet_id") val playletId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("list") val list: List<ChapterItem>? = null,
    )

    data class ChapterItem(
        @JsonProperty("chapter_id") val chapterId: String? = null,
        @JsonProperty("chapter_title") val chapterTitle: String? = null,
        @JsonProperty("chapter_num") val chapterNum: Int? = null,
        @JsonProperty("chapter_cover") val chapterCover: String? = null,
        @JsonProperty("hls_url") val hlsUrl: String? = null,
        @JsonProperty("is_lock") val isLock: Int? = null,
        @JsonProperty("is_need_pay") val isNeedPay: Int? = null,
        @JsonProperty("is_vip_episode") val isVipEpisode: Int? = null,
    )

    data class LoadData(
        @JsonProperty("playletId") val playletId: String? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("hlsUrl") val hlsUrl: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
    )
}
