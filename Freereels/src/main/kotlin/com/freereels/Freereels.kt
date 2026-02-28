package com.freereels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Freereels : MainAPI() {
    override var mainUrl = "https://api.sansekai.my.id"
    override var name = "Freereels🎀"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/freereels/foryou" to "Untukmu",
        "/api/freereels/homepage" to "Beranda",
        "/api/freereels/animepage" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>())

        val items = fetchItems(request.data)
            .distinctBy { it.id }
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val fromApi = fetchItems("/api/freereels/search?query=$encoded")

        val items = if (fromApi.isNotEmpty()) {
            fromApi
        } else {
            // Fallback kalau endpoint search kosong.
            val sourcePaths = listOf(
                "/api/freereels/foryou",
                "/api/freereels/homepage",
                "/api/freereels/animepage",
            )
            sourcePaths
                .flatMap { fetchItems(it) }
                .distinctBy { it.id }
                .filter {
                    it.title.contains(q, true) || it.description.orEmpty().contains(q, true)
                }
        }

        return items.mapNotNull { it.toSearchResponse() }
    }

    private fun SeriesItem.toSearchResponse(): SearchResponse? {
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(
            title,
            "$mainUrl/series/$id",
            TvType.AsianDrama
        ) {
            posterUrl = cover
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesId = extractSeriesId(url)
        val detail = fetchDetail(seriesId)
            ?: throw ErrorLoadingException("Detail tidak ditemukan")

        val episodes = detail.episodes.mapIndexed { idx, ep ->
            val epNumber = ep.index ?: (idx + 1)
            newEpisode(
                LoadData(seriesId = detail.id, episodeId = ep.id, index = ep.index).toJson()
            ) {
                name = if (ep.name.isNotBlank()) "EP $epNumber. ${ep.name}" else "EP $epNumber"
                episode = epNumber
                posterUrl = ep.cover ?: detail.cover
            }
        }

        return newTvSeriesLoadResponse(
            name = detail.title,
            url = "$mainUrl/series/${detail.id}",
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            posterUrl = detail.cover
            plot = detail.description
            tags = detail.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LoadData>(data)
        val seriesId = parsed.seriesId ?: return false
        val detail = fetchDetail(seriesId) ?: return false

        val episode = detail.episodes.firstOrNull { it.id == parsed.episodeId }
            ?: detail.episodes.firstOrNull { parsed.index != null && it.index == parsed.index }
            ?: return false

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
        )

        val streams = listOfNotNull(
            episode.h264?.takeIf { it.isNotBlank() }?.let { "H264" to it },
            episode.h265?.takeIf { it.isNotBlank() }?.let { "H265" to it },
            episode.m3u8?.takeIf { it.isNotBlank() }?.let { "M3U8" to it },
            episode.videoUrl?.takeIf { it.isNotBlank() }?.let { "Video" to it },
        ).distinctBy { it.second }

        streams.forEach { (label, streamUrl) ->
            if (streamUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    source = "$name $label",
                    streamUrl = streamUrl,
                    referer = mainUrl,
                    headers = headers
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = headers
                    }
                )
            }
        }

        episode.subtitles.forEach {
            subtitleCallback(
                newSubtitleFile(
                    lang = it.displayName ?: it.language ?: "Subtitle",
                    url = it.url
                )
            )
        }

        return streams.isNotEmpty()
    }

    private suspend fun fetchItems(path: String): List<SeriesItem> {
        val body = app.get("$mainUrl$path").text
        return runCatching { parseItemsFromRoot(body) }.getOrDefault(emptyList())
    }

    private fun parseItemsFromRoot(body: String): List<SeriesItem> {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return emptyList()
        val result = mutableListOf<SeriesItem>()

        fun appendFromArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.has("items") && obj.optJSONArray("items") != null) {
                    appendFromArray(obj.optJSONArray("items"))
                } else {
                    parseSeriesItem(obj)?.let { result.add(it) }
                }
            }
        }

        appendFromArray(data.optJSONArray("items"))
        // Endpoint detail/search tertentu bisa taruh data langsung dalam info/series.
        data.optJSONObject("info")?.let { info ->
            if (info.has("id")) parseSeriesItem(info)?.let { result.add(it) }
        }

        return result
    }

    private suspend fun fetchDetail(seriesId: String): DetailData? {
        val body = app.get("$mainUrl/api/freereels/detailAndAllEpisode?id=$seriesId").text
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return null
        val info = data.optJSONObject("info") ?: return null

        val episodes = mutableListOf<EpisodeData>()
        val episodeArr = info.optJSONArray("episode_list")
        if (episodeArr != null) {
            for (i in 0 until episodeArr.length()) {
                val ep = episodeArr.optJSONObject(i) ?: continue
                episodes.add(parseEpisode(ep))
            }
        }

        val tags = mutableListOf<String>()
        tags.addAll(info.optStringList("tag"))
        tags.addAll(info.optStringList("content_tags"))
        tags.addAll(info.optStringList("series_tag"))

        val id = info.optString("id")
        val title = info.optString("name")
        if (id.isBlank() || title.isBlank()) return null

        return DetailData(
            id = id,
            title = title,
            description = info.optStringSafe("desc"),
            cover = info.optStringSafe("cover"),
            tags = tags.distinct(),
            episodes = episodes.sortedBy { it.index ?: Int.MAX_VALUE }
        )
    }

    private fun parseSeriesItem(obj: JSONObject): SeriesItem? {
        val id = obj.optStringSafe("key") ?: obj.optStringSafe("id") ?: return null
        val title = obj.optStringSafe("title") ?: obj.optStringSafe("name") ?: return null
        if (title.isBlank()) return null

        val tags = mutableListOf<String>()
        tags.addAll(obj.optStringList("tag"))
        tags.addAll(obj.optStringList("series_tag"))
        tags.addAll(obj.optStringList("content_tags"))

        return SeriesItem(
            id = id,
            title = title,
            description = obj.optStringSafe("desc"),
            cover = obj.optStringSafe("cover"),
            episodeCount = obj.optInt("episode_count").takeIf { it > 0 },
            tags = tags.distinct(),
        )
    }

    private fun parseEpisode(obj: JSONObject): EpisodeData {
        val subtitles = mutableListOf<SubtitleData>()
        val subtitleArr = obj.optJSONArray("subtitle_list")
        if (subtitleArr != null) {
            for (i in 0 until subtitleArr.length()) {
                val sub = subtitleArr.optJSONObject(i) ?: continue
                val subUrl = sub.optStringSafe("subtitle") ?: continue
                subtitles.add(
                    SubtitleData(
                        language = sub.optStringSafe("language"),
                        displayName = sub.optStringSafe("display_name"),
                        url = subUrl
                    )
                )
            }
        }

        return EpisodeData(
            id = obj.optStringSafe("id"),
            index = obj.optInt("index").takeIf { it > 0 },
            name = obj.optStringSafe("name").orEmpty(),
            cover = obj.optStringSafe("cover"),
            h264 = obj.optStringSafe("external_audio_h264_m3u8"),
            h265 = obj.optStringSafe("external_audio_h265_m3u8"),
            m3u8 = obj.optStringSafe("m3u8_url"),
            videoUrl = obj.optStringSafe("video_url"),
            subtitles = subtitles
        )
    }

    private fun extractSeriesId(url: String): String {
        val fromQuery = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return url.substringAfterLast("/").substringBefore("?")
    }

    private fun JSONObject.optStringSafe(key: String): String? {
        val value = optString(key).trim()
        return value.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank() && !value.equals("null", true)) out.add(value)
        }
        return out
    }

    data class LoadData(
        @JsonProperty("seriesId") val seriesId: String? = null,
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("index") val index: Int? = null,
    )

    data class SeriesItem(
        val id: String,
        val title: String,
        val description: String?,
        val cover: String?,
        val episodeCount: Int?,
        val tags: List<String>,
    )

    data class DetailData(
        val id: String,
        val title: String,
        val description: String?,
        val cover: String?,
        val tags: List<String>,
        val episodes: List<EpisodeData>,
    )

    data class EpisodeData(
        val id: String?,
        val index: Int?,
        val name: String,
        val cover: String?,
        val h264: String?,
        val h265: String?,
        val m3u8: String?,
        val videoUrl: String?,
        val subtitles: List<SubtitleData>,
    )

    data class SubtitleData(
        val language: String?,
        val displayName: String?,
        val url: String,
    )
}
