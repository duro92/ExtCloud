package com.shortmax

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class Shortmax : MainAPI() {
    override var mainUrl = "https://api.sansekai.my.id"
    override var name = "Shortmax"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/api/shortmax/foryou" to "Untukmu",
        "/api/shortmax/latest" to "Terbaru",
        "/api/shortmax/rekomendasi" to "Rekomendasi",
        "/api/shortmax/vip" to "VIP",
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
        val fromSearch = fetchItems("/api/shortmax/search?query=$encoded")
        val items = if (fromSearch.isNotEmpty()) {
            fromSearch
        } else {
            listOf(
                "/api/shortmax/foryou",
                "/api/shortmax/latest",
                "/api/shortmax/rekomendasi",
                "/api/shortmax/vip",
            ).flatMap { fetchItems(it) }
                .distinctBy { it.id }
                .filter {
                    it.title.contains(q, true) || it.description.orEmpty().contains(q, true)
                }
        }

        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val shortPlayId = extractShortPlayId(url)
        val detail = fetchDetail(shortPlayId)
            ?: throw ErrorLoadingException("Detail Shortmax tidak ditemukan")

        val episodes = (1..detail.totalEpisodes).map { epNum ->
            newEpisode(
                LoadData(shortPlayId = detail.shortPlayId, episodeNumber = epNum).toJson()
            ) {
                name = "EP $epNum"
                episode = epNum
                posterUrl = detail.cover
            }
        }

        return newTvSeriesLoadResponse(
            name = detail.title,
            url = "$mainUrl/shortplay/${detail.shortPlayId}",
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
        val payload = parseJson<LoadData>(data)
        val shortPlayId = payload.shortPlayId ?: return false
        val episodeNumber = payload.episodeNumber ?: return false

        val body = runCatching {
            app.get("$mainUrl/api/shortmax/episode?shortPlayId=$shortPlayId&episodeNumber=$episodeNumber").text
        }.getOrNull() ?: return false

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (!root.optString("status").equals("ok", true)) return false

        val episode = root.optJSONObject("episode") ?: return false
        val videoObj = episode.optJSONObject("videoUrl") ?: return false

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to mainUrl,
            "Origin" to mainUrl,
        )

        val links = mutableListOf<Pair<String, String>>()
        videoObj.keys().forEach { key ->
            val streamUrl = videoObj.optString(key).trim()
            if (streamUrl.isBlank() || streamUrl.equals("null", true)) return@forEach
            val quality = Regex("(\\d{3,4})").find(key)?.groupValues?.getOrNull(1) ?: "Auto"
            links.add("Shortmax $quality" to streamUrl)
        }

        links.distinctBy { it.second }.forEach { (label, streamUrl) ->
            if (streamUrl.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = streamUrl,
                    referer = mainUrl,
                    headers = headers
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = headers
                    }
                )
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun fetchItems(path: String): List<ShortmaxItem> {
        val body = runCatching { app.get("$mainUrl$path").text }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!root.optString("status").equals("ok", true)) return emptyList()

        val arr = root.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<ShortmaxItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optStringSafe("shortPlayId")
                ?: obj.optStringSafe("id")
                ?: continue
            val title = obj.optStringSafe("name") ?: obj.optStringSafe("title") ?: continue
            if (title.isBlank()) continue

            out.add(
                ShortmaxItem(
                    id = id,
                    title = title,
                    cover = obj.optStringSafe("cover") ?: obj.optStringSafe("picUrl"),
                    description = obj.optStringSafe("summary"),
                    totalEpisodes = obj.optInt("totalEpisodes").takeIf { it > 0 },
                )
            )
        }
        return out
    }

    private suspend fun fetchDetail(shortPlayId: String): ShortmaxDetail? {
        val body = runCatching {
            app.get("$mainUrl/api/shortmax/detail?shortPlayId=$shortPlayId").text
        }.getOrNull() ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optString("status").equals("ok", true)) return null
        val data = root.optJSONObject("data") ?: return null

        val title = data.optStringSafe("shortPlayName")
            ?: data.optStringSafe("name")
            ?: return null
        val totalEpisodes = data.optInt("totalEpisodes").takeIf { it > 0 } ?: return null

        val tags = mutableListOf<String>()
        val labelResponse = data.optJSONArray("labelResponseList")
        if (labelResponse != null) {
            for (i in 0 until labelResponse.length()) {
                val tagObj = labelResponse.optJSONObject(i) ?: continue
                val tagName = tagObj.optStringSafe("labelName") ?: continue
                tags.add(tagName)
            }
        }

        return ShortmaxDetail(
            shortPlayId = (data.optLong("id").takeIf { it > 0 }?.toString())
                ?: data.optStringSafe("id")
                ?: shortPlayId,
            title = title,
            cover = data.optStringSafe("picUrl") ?: data.optStringSafe("cover"),
            description = data.optStringSafe("summary"),
            totalEpisodes = totalEpisodes,
            tags = tags.distinct(),
        )
    }

    private fun extractShortPlayId(url: String): String {
        val fromQuery = Regex("[?&](?:shortPlayId|id)=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return url.substringAfterLast("/").substringBefore("?")
    }

    private fun JSONObject.optStringSafe(key: String): String? {
        val value = optString(key).trim()
        return value.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }

    private fun ShortmaxItem.toSearchResponse(): SearchResponse? {
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(
            title,
            "$mainUrl/shortplay/$id",
            TvType.AsianDrama
        ) {
            posterUrl = cover
        }
    }

    data class LoadData(
        @JsonProperty("shortPlayId") val shortPlayId: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    )

    data class ShortmaxItem(
        val id: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val totalEpisodes: Int?,
    )

    data class ShortmaxDetail(
        val shortPlayId: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val totalEpisodes: Int,
        val tags: List<String>,
    )
}
