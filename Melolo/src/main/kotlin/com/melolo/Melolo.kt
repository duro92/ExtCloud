package com.melolo

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder


class Melolo : MainAPI() {
    override var mainUrl = Endpoints.apiBase
    override var name = "Melolo😶"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val aid = "645713"
    private val catalogBase = Endpoints.catalogBase

    override val mainPage = mainPageOf(
        "latest" to "Terbaru",
        "trending" to "Trending",
        // Search-backed categories (paginated via limit/offset)
        "q:ceo" to "CEO",
        "q:romansa" to "Romansa",
        "q:sistem" to "Sistem",
        "q:keluarga" to "Keluarga",
        "q:mafia" to "Mafia",
        "q:aksi" to "Aksi",
        "q:balas dendam" to "Balas Dendam",
        "q:pernikahan" to "Pernikahan",
        "q:drama periode" to "Drama Periode",
    )

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private fun seriesUrl(bookId: String) = "$mainUrl/series/$bookId"

    private fun bookIdFromUrl(url: String): String {
   
        return url.substringAfterLast("/").substringBefore("?").trim()
    }

    // -----------------------------
    // Catalog models (3rd party)
    // -----------------------------
    data class CatalogLatestResponse(
        val books: List<CatalogBook> = emptyList(),
    )

    data class CatalogTrendingResponse(
        val books: List<CatalogBook> = emptyList(),
    )

    // Search response has a different shape: { code, data: { has_more, next_offset, search_data:[{books:[...]}] } }
    data class CatalogSearchResponse(
        val code: Int? = null,
        val data: CatalogSearchData? = null,
    )

    data class CatalogSearchData(
        val has_more: Boolean? = null,
        val next_offset: Int? = null,
        val search_data: List<CatalogSearchBlock> = emptyList(),
    )

    data class CatalogSearchBlock(
        val books: List<CatalogBook> = emptyList(),
    )

    data class CatalogBook(
        val book_id: String? = null,
        val book_name: String? = null,
        val abstract: String? = null,
        val thumb_url: String? = null,
        val language: String? = null,
    )

    data class CatalogDetailResponse(
        val data: CatalogDetailData? = null,
    )

    data class CatalogDetailData(
        val video_data: CatalogVideoData? = null,
    )

    data class CatalogVideoData(
        val series_id_str: String? = null,
        val series_title: String? = null,
        val series_intro: String? = null,
        val series_cover: String? = null,
        val episode_cnt: Int? = null,
        val video_list: List<CatalogEpisode> = emptyList(),
        val video_platform: Int? = null,
    )

    data class CatalogEpisode(
        val vid: String? = null,
        val vid_index: Int? = null,
        val cover: String? = null,
        val duration: Int? = null,
        val disable_play: Boolean? = null,
    )

    // -----------------------------
    // Player models (official)
    // -----------------------------
    data class PlayerBaseResp(
        val StatusCode: Int? = null,
        val StatusMessage: String? = null,
    )

    data class PlayerVideoModelResponse(
        val BaseResp: PlayerBaseResp? = null,
        val code: Int? = null,
        val data: PlayerVideoModelData? = null,
        val message: String? = null,
    )

    data class PlayerVideoModelData(
        val main_url: String? = null,
        val backup_url: String? = null,
        val expire_time: Long? = null,
    )

    data class EpisodeData(
        val bookId: String,
        val seriesId: String,
        val vid: String,
        val episode: Int,
        val videoPlatform: Int = 3, // default: Outer
    )

    private suspend fun fetchLatest(): List<CatalogBook> {
        val text = app.get("$catalogBase/latest", timeout = 30L).text
        return tryParseJson<CatalogLatestResponse>(text)?.books.orEmpty()
            .filter { it.language.equals("id", ignoreCase = true) }
    }

    private suspend fun fetchTrending(): List<CatalogBook> {
        val text = app.get("$catalogBase/trending", timeout = 30L).text
        return tryParseJson<CatalogTrendingResponse>(text)?.books.orEmpty()
            .filter { it.language.equals("id", ignoreCase = true) }
    }

    private suspend fun fetchSearch(query: String, limit: Int, offset: Int): List<CatalogBook> {
        val url = "$catalogBase/search?query=${query.urlEncode()}&limit=$limit&offset=$offset"
        val text = app.get(url, timeout = 30L).text
        val resp = tryParseJson<CatalogSearchResponse>(text)
        val books = resp?.data?.search_data?.flatMap { it.books }.orEmpty()
        return books.filter { it.language.equals("id", ignoreCase = true) }
    }

    private suspend fun fetchSearchPage(
        query: String,
        limit: Int,
        offset: Int,
    ): Pair<List<CatalogBook>, Boolean> {
        val url = "$catalogBase/search?query=${query.urlEncode()}&limit=$limit&offset=$offset"
        val text = app.get(url, timeout = 30L).text
        val resp = tryParseJson<CatalogSearchResponse>(text)
        val books = resp?.data?.search_data?.flatMap { it.books }.orEmpty()
            .filter { it.language.equals("id", ignoreCase = true) }
        val hasMore = resp?.data?.has_more == true
        return books to hasMore
    }

    private suspend fun fetchDetail(bookId: String): CatalogVideoData {
        val text = app.get("$catalogBase/detail/$bookId", timeout = 30L).text
        val resp = tryParseJson<CatalogDetailResponse>(text)
        return resp?.data?.video_data ?: throw Error("Empty detail data")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // latest/trending are not paginated by the catalog API; keep them as page=1 only.
        // search-backed categories are paginated (page -> offset).
        val isSearchCategory = request.data.startsWith("q:", ignoreCase = true)
        if (page > 1 && !isSearchCategory) return newHomePageResponse(
            HomePageList(request.name, emptyList()),
            hasNext = false
        )

        val (books, hasNext) = if (isSearchCategory) {
            val query = request.data.removePrefix("q:").trim()
            val limit = 20
            val offset = (page.coerceAtLeast(1) - 1) * limit
            val (b, more) = fetchSearchPage(query, limit = limit, offset = offset)
            b to more
        } else {
            val b = when (request.data) {
                "trending" -> fetchTrending()
                else -> fetchLatest()
            }
            b to false
        }

        val items = books.mapNotNull { b ->
            val bookId = b.book_id ?: return@mapNotNull null
            val title = b.book_name ?: return@mapNotNull null
            newTvSeriesSearchResponse(
                name = title,
                url = seriesUrl(bookId),
                type = TvType.TvSeries,
            ) {
                posterUrl = b.thumb_url
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Keep it small and fast; Cloudstream will call repeatedly.
        val books = fetchSearch(query, limit = 20, offset = 0)
        return books.mapNotNull { b ->
            val bookId = b.book_id ?: return@mapNotNull null
            val title = b.book_name ?: return@mapNotNull null
            newTvSeriesSearchResponse(
                name = title,
                url = seriesUrl(bookId),
                type = TvType.TvSeries,
            ) {
                posterUrl = b.thumb_url
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val limit = 20
        val offset = (page.coerceAtLeast(1) - 1) * limit
        val (books, _) = fetchSearchPage(query, limit = limit, offset = offset)
        val items = books.mapNotNull { b ->
            val bookId = b.book_id ?: return@mapNotNull null
            val title = b.book_name ?: return@mapNotNull null
            newTvSeriesSearchResponse(
                name = title,
                url = seriesUrl(bookId),
                type = TvType.TvSeries,
            ) {
                posterUrl = b.thumb_url
            }
        }
        return items.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = bookIdFromUrl(url)
        if (bookId.isBlank()) throw Error("Invalid series url")

        val detail = fetchDetail(bookId)
        val seriesId = detail.series_id_str ?: bookId
        val title = detail.series_title ?: "Melolo"

        val episodes = detail.video_list
            .filter { it.disable_play != true }
            .mapNotNull { ep ->
                val vid = ep.vid ?: return@mapNotNull null
                val idx = ep.vid_index ?: return@mapNotNull null
                newEpisode(
                    data = EpisodeData(
                        bookId = bookId,
                        seriesId = seriesId,
                        vid = vid,
                        episode = idx,
                        videoPlatform = detail.video_platform ?: 3
                    ).toJson(),
                ) {
                    name = "Episode $idx"
                    posterUrl = ep.cover
                    episode = idx
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(
            name = title,
            url = seriesUrl(bookId),
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            plot = detail.series_intro
            posterUrl = detail.series_cover
        }
    }

    private fun buildPlayerBody(episode: EpisodeData): String {
        // video_id_type = 0 (VideoId), device_level = 1 (Low), video_platform = 3 (Outer)
        val payload = mapOf(
            "video_id" to episode.vid,
            "biz_param" to mapOf(
                "video_id_type" to 0,
                "device_level" to 1,
                "video_platform" to episode.videoPlatform,
            ),
            "NovelCommonParam" to mapOf(
                "app_language" to "id",
                "sys_language" to "id",
                "user_language" to "id",
                "ui_language" to "id",
                "language" to "id",
                "region" to "ID",
                "current_region" to "ID",
                "app_region" to "ID",
                "sys_region" to "ID",
                "carrier_region" to "ID",
                "carrier_region_v2" to "ID",
                "fake_priority_region" to "ID",
                "time_zone" to "Asia/Jakarta",
                "mcc_mnc" to "51011",
            ),
        )
        return payload.toJson()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<EpisodeData>(data) ?: return false

        val body = buildPlayerBody(ep).toRequestBody("application/json".toMediaType())
        val apiUrl = "$mainUrl/novel/player/video_model/v1/?aid=$aid"

        val respText = app.post(
            apiUrl,
            requestBody = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                // App biasanya false, Lynx/bridge biasanya true. Untuk player endpoint ini, false bekerja.
                "X-Xs-From-Web" to "false",
                "User-Agent" to "okhttp/4.9.3",
                "Referer" to "$mainUrl/",
            ),
        ).text

        val resp = tryParseJson<PlayerVideoModelResponse>(respText)
        val main = resp?.data?.main_url
        val backup = resp?.data?.backup_url

        suspend fun emit(url: String) {
            callback(
                newExtractorLink(
                    name,
                    "Melolo",
                    url,
                    ExtractorLinkType.VIDEO,
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("User-Agent" to "okhttp/4.9.3")
                }
            )
        }

        if (!main.isNullOrBlank()) emit(main)
        if (!backup.isNullOrBlank() && backup != main) emit(backup)

        return !main.isNullOrBlank() || !backup.isNullOrBlank()
    }

    private object Endpoints {
        private fun d(s: String): String =
            String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)

        val apiBase: String = d("aHR0cHM6Ly9hcGkudG10cmVhZGVyLmNvbQ==")
        val catalogBase: String =
            d("aHR0cHM6Ly9tZWxvbG8tYXBpLWF6dXJlLnZlcmNlbC5hcHAvYXBpL21lbG9sbw==")
    }
}
