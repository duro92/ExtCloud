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
    override var name = "FlickReels😘"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "foryou" to "Untuk Kamu",
        "rank" to "Ranking",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when (request.data) {
            "foryou" -> fetchForYou(page)
            "rank" -> if (page == 1) fetchRank() else emptyList()
            else -> emptyList()
        }

        val results = items
            .distinctBy { it.id }
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, results),
            hasNext = request.data == "foryou" && results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val items = (fetchRank() + fetchForYou(1))
            .distinctBy { it.id }
            .filter { it.title.orEmpty().contains(keyword, true) }
            .mapNotNull { it.toSearchResult() }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val playletId = extractPlayletId(url)
        if (playletId.isBlank()) throw ErrorLoadingException("ID tidak ditemukan")

        val detail = fetchChapters(playletId)
        val episodes = detail?.list
            .orEmpty()
            .sortedWith(
                compareBy<ChapterItem> { it.chapterNum ?: Int.MAX_VALUE }
                    .thenBy { it.chapterId ?: "" }
            )
            .mapIndexed { index, chapter ->
                val number = chapter.chapterNum ?: (index + 1)
                val baseName = chapter.chapterTitle?.takeIf { it.isNotBlank() } ?: "Episode $number"
                val displayName = baseName

                newEpisode(
                    LoadData(
                        playletId = playletId,
                        episodeNo = number
                    ).toJsonData()
                ) {
                    name = displayName
                    this.episode = number
                    posterUrl = chapter.chapterCover
                }
            }

        val title = detail?.title?.takeIf { it.isNotBlank() } ?: "FlickReels"
        val safeUrl = buildPlayletUrl(playletId)

        return newTvSeriesLoadResponse(title, safeUrl, TvType.AsianDrama, episodes) {
            posterUrl = detail?.cover
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val playletId = parsed.playletId ?: return false
        val episodeNo = parsed.episodeNo ?: return false

        val stream = fetchStream(playletId, episodeNo) ?: return false
        val videoUrl = stream.hlsUrl?.trim().orEmpty()
        if (videoUrl.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "FlickReels",
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            }
        )

        return true
    }

    private suspend fun fetchForYou(page: Int): List<PlayletItem> {
        val safePage = if (page < 1) 1 else page
        val url = "$mainUrl/api/foryou?page=$safePage&lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<PlayletResponse>(body)?.data.orEmpty()
    }

    private suspend fun fetchRank(): List<PlayletItem> {
        val url = "$mainUrl/api/rank?lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return emptyList()
        return tryParseJson<PlayletResponse>(body)?.data.orEmpty()
    }

    private suspend fun fetchChapters(playletId: String): ChaptersData? {
        val url = "$mainUrl/api/chapters?id=$playletId&lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<ChaptersResponse>(body)?.data
    }

    private suspend fun fetchStream(playletId: String, episodeNo: Int): StreamData? {
        val url = "$mainUrl/api/stream?id=$playletId&ep=$episodeNo&lang=$lang"
        val body = runCatching { app.get(url).text }.getOrNull() ?: return null
        return tryParseJson<StreamResponse>(body)?.data
    }

    private fun PlayletItem.toSearchResult(): SearchResponse? {
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

    data class PlayletResponse(
        @JsonProperty("data") val data: List<PlayletItem>? = null,
    )

    data class PlayletItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("episodes") val episodes: String? = null,
    )

    data class ChaptersResponse(
        @JsonProperty("data") val data: ChaptersData? = null,
        @JsonProperty("status_code") val statusCode: Int? = null,
        @JsonProperty("msg") val message: String? = null,
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
        @JsonProperty("is_lock") val isLock: Int? = null,
        @JsonProperty("hls_url") val hlsUrl: String? = null,
    )

    data class StreamResponse(
        @JsonProperty("data") val data: StreamData? = null,
        @JsonProperty("status_code") val statusCode: Int? = null,
        @JsonProperty("msg") val message: String? = null,
    )

    data class StreamData(
        @JsonProperty("playlet_id") val playletId: String? = null,
        @JsonProperty("chapter_id") val chapterId: String? = null,
        @JsonProperty("chapter_num") val chapterNum: Int? = null,
        @JsonProperty("hls_url") val hlsUrl: String? = null,
    )

    data class LoadData(
        @JsonProperty("playletId") val playletId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
    )
}
