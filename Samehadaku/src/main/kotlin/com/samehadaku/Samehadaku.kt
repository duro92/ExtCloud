package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {

    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://v1.samehadaku.how"
    override var name = "Samehadaku⛩️"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "daftar-anime-2/?title=&status=&type=TV&order=popular&page=" to "TV Populer",
        "daftar-anime-2/?title=&status=&type=OVA&order=title&page=" to "OVA",
        "daftar-anime-2/?title=&status=&type=Movie&order=title&page=" to "Movie"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }

        if (request.name == "Episode Terbaru") {
            val document = app.get("${request.data}$page").document

            val home = document.select("div.post-show ul li").mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null

                val rawTitle = a.attr("title").ifBlank { a.text() }
                val title = rawTitle
                    .replace(Regex("(Episode|Ep)\\s*\\d+", RegexOption.IGNORE_CASE), "")
                    .removeBloat()
                    .trim()

                val href = fixUrl(a.attr("href"))
                val poster = fixUrlNull(li.selectFirst("img")?.attr("src"))

                val ep = Regex("(Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(li.text())
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    posterUrl = poster
                    addSub(ep)
                }
            }

            return newHomePageResponse(
                HomePageList(request.name, home, true),
                hasNext = home.isNotEmpty()
            )
        }

        val document = app.get("$mainUrl/${request.data}$page").document
        val home = document.select("div.animposx").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = selectFirst("a") ?: return null

        val title = a.attr("title").ifBlank {
            selectFirst("div.title, h2.entry-title a, div.lftinfo h2")?.text()
        } ?: return null

        val href = fixUrl(a.attr("href"))
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))

        val type = when {
            href.contains("/ova/", true) -> TvType.OVA
            href.contains("/movie/", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title.trim(), href, type) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query")
            .document
            .select("div.animposx")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.removeBloat()
            ?: return null

        val poster = document.selectFirst("div.thumb img")?.attr("src")
        val description = document.select("div.desc p").text()
        val tags = document.select("div.genre-info a").map { it.text() }

        val year = document.selectFirst("div.spe span:contains(Rilis)")
            ?.ownText()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val status = when (
            document.selectFirst("div.spe span:contains(Status)")?.ownText()
        ) {
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val type = when {
            url.contains("/ova/", true) -> TvType.OVA
            url.contains("/movie/", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val trailer = document
            .selectFirst("iframe[src*=\"youtube\"]")
            ?.attr("src")

        val episodes = document.select("div.lstepsiode ul li")
            .mapNotNull {
                val a = it.selectFirst("a") ?: return@mapNotNull null

                val ep = Regex("(Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(a.text())
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                newEpisode(fixUrl(a.attr("href"))) {
                    episode = ep
                }
            }
            .reversed()

        val tracker = APIHolder.getTracker(
            listOf(title),
            TrackerType.getTypes(type),
            year,
            true
        )

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            plot = description
            this.tags = tags
            this.year = year
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
            addTrailer(trailer)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        app.get(data).document
            .select("div#downloadb li")
            .amap { li ->
                val quality = li.select("strong").text()
                li.select("a").amap { a ->
                    loadFixedExtractor(
                        fixUrl(a.attr("href")),
                        quality,
                        subtitleCallback,
                        callback
                    )
                }
            }
        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, subtitleCallback = subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        referer = link.referer
                        this.quality = quality.fixQuality()
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun String.removeBloat(): String =
        replace(
            Regex("(Nonton|Anime|Subtitle\\s*Indonesia)", RegexOption.IGNORE_CASE),
            ""
        ).trim()
}