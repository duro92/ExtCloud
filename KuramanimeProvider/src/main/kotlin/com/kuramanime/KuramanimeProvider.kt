package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://v14.kuramanime.tel"
    override var name = "Kuramanime🐱"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$mainUrl/anime?search=$query&order_by=latest"
        ).document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags =
            document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
                .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..30) {
            val doc = app.get("$url?page=$i").document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content"))
                .select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toIntOrNull()
                    val link = it.attr("href")
                    newEpisode(link) { this.episode = episode }
                }
            if (eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(
            document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()
                ?.lowercase() ?: "tv", episodes.size
        )
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    private suspend fun emitPublicVideoSources(
        document: org.jsoup.nodes.Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = mutableSetOf<String>()

        suspend fun emit(rawUrl: String?, qualityHint: String? = null) {
            val cleaned = rawUrl?.trim()?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return
            val fixed = fixUrl(cleaned)
            if (!emitted.add(fixed)) return

            val quality = qualityHint?.toIntOrNull()
                ?: Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
                    .find(fixed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: Qualities.Unknown.value

            val type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE

            callback.invoke(
                newExtractorLink(
                    source = "Kuramanime",
                    name = "Kuramanime",
                    url = fixed,
                    type = type
                ) {
                    this.referer = pageUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to pageUrl,
                        "Origin" to mainUrl,
                        "Accept" to "*/*",
                        "Range" to "bytes=0-"
                    )
                }
            )
        }

        suspend fun emitExtractor(rawUrl: String?) {
            val cleaned = rawUrl?.trim()?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return
            val fixed = fixUrl(cleaned)
            if (!emitted.add(fixed)) return
            loadExtractor(fixed, pageUrl, subtitleCallback, callback)
        }

        val player = document.selectFirst("video#player, video#anime_player, video")
        emit(player?.attr("data-hls-src"))
        emit(player?.attr("data-src"))
        emit(player?.attr("src"))

        for (it in document.select("video#player source[src], video source[src]")) {
            emit(it.attr("src"), it.attr("size"))
        }

        for (it in document.select("div.iframe-container iframe[src], iframe[src]")) {
            emitExtractor(it.attr("src"))
        }

        return emitted.isNotEmpty()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data)
        val res = req.document
        return emitPublicVideoSources(res, req.url, subtitleCallback, callback)
    }

}
