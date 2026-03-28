package com.auratail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AuraTail : MainAPI() {
    override var mainUrl = "https://auratail.vip"
    override var name = "AuraTail"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    companion object {
        fun getType(typeText: String?): TvType {
            if (typeText == null) return TvType.Anime
            return when {
                typeText.contains("tv", true) -> TvType.Anime
                typeText.contains("movie", true) -> TvType.AnimeMovie
                typeText.contains("ova", true) || typeText.contains("special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(statusText: String?): ShowStatus {
            if (statusText == null) return ShowStatus.Completed
            return when {
                statusText.contains("ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "page/%d/" to "Update Terbaru",
        "page/%d/" to "Movie",
        "page/%d/" to "TV Series",
        "page/%d/" to "ONA",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(fixUrl(request.data.format(page))).document
        val items =
            document
                .select("div.listupd article.bs")
                .mapNotNull { card ->
                    val type = getType(card.selectFirst(".typez, .limit .type, span.type")?.text()?.trim())
                    if (!matchesMainPage(request.name, type)) return@mapNotNull null
                    card.toSearchResult()
                }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document
        val rawHtml = response.text
        val pageTitle =
            document
                .selectFirst("h1.entry-title")
                ?.text()
                ?.replace(
                    Regex(
                        "\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        val seriesMeta = extractSeriesMeta(rawHtml)
        val isEpisodePage =
            url.contains("-episode-", true) ||
                Regex("\\bEpisode\\s+\\d+\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageTitle)

        val title =
            if (isEpisodePage) {
                seriesMeta?.seriesTitle ?: pageTitle.removeEpisodeSuffix()
            } else {
                pageTitle
            }
        val poster =
            seriesMeta?.posterUrl
                ?: document
                    .selectFirst("div.bigcontent img, div.thumb img, .thumb img, meta[property=og:image]")
                    ?.let { element ->
                        if (element.tagName().equals("meta", true)) {
                            fixUrlNull(element.attr("content"))
                        } else {
                            element.getImageAttr().let { fixUrlNull(it) }
                        }
                    }

        val description =
            document
                .select("div.entry-content p")
                .joinToString("\n") { it.text() }
                .trim()

        val year =
            document
                .selectFirst("span:matchesOwn(Dirilis:)")
                ?.ownText()
                ?.filter { it.isDigit() }
                ?.take(4)
                ?.toIntOrNull()

        val duration =
            document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
                val hours =
                    Regex("(\\d+)\\s*hr", RegexOption.IGNORE_CASE)
                        .find(it)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0
                val minutes =
                    Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
                        .find(it)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0
                hours * 60 + minutes
            }

        val typeText = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val type = if (isEpisodePage) TvType.Anime else getType(typeText)

        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a").map { it.text().trim() }
        val rating =
            document
                .selectFirst("div.rating strong")
                ?.text()
                ?.replace("Rating", "")
                ?.trim()
                ?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val status = getStatus(document.selectFirst("span:matchesOwn(Status:)")?.ownText()?.trim())

        val recommendations =
            document
                .select("div.listupd article.bs")
                .mapNotNull { it.toRecommendResult() }

        val castList =
            document.select("div.bixbox.charvoice div.cvitem").mapNotNull { item ->
                val charBox = item.selectFirst(".cvsubitem.cvchar") ?: item
                val actorBox = item.selectFirst(".cvsubitem.cvactor") ?: item

                val charName = charBox.selectFirst(".cvcontent .charname")?.text()?.trim()
                val charRole = charBox.selectFirst(".cvcontent .charrole")?.text()?.trim()
                val charImg = charBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

                val actorName = actorBox.selectFirst(".cvcontent .charname")?.text()?.trim()
                val actorImg = actorBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

                if (charName.isNullOrBlank() && actorName.isNullOrBlank()) return@mapNotNull null

                val actor = Actor(charName ?: actorName ?: "", charImg)
                val voiceActor = actorName?.let { Actor(it, actorImg) }
                ActorData(actor = actor, roleString = charRole, voiceActor = voiceActor)
            }

        val episodes = buildEpisodes(document, isEpisodePage)

        val altTitles =
            listOfNotNull(
                title,
                pageTitle.takeIf { it.isNotBlank() && !it.equals(title, true) }?.removeEpisodeSuffix(),
                seriesMeta?.seriesTitle,
                document.selectFirst("span:matchesOwn(Judul Inggris:)")?.ownText()?.trim(),
                document.selectFirst("span:matchesOwn(Judul Jepang:)")?.ownText()?.trim(),
                document.selectFirst("span:matchesOwn(Judul Asli:)")?.ownText()?.trim(),
            ).distinct()

        val malIdFromPage =
            document
                .selectFirst("a[href*=\"myanimelist.net/anime/\"]")
                ?.attr("href")
                ?.substringAfter("/anime/", "")
                ?.substringBefore("/")
                ?.toIntOrNull()
        val aniIdFromPage =
            document
                .selectFirst("a[href*=\"anilist.co/anime/\"]")
                ?.attr("href")
                ?.substringAfter("/anime/", "")
                ?.substringBefore("/")
                ?.toIntOrNull()

        val tracker = APIHolder.getTracker(altTitles, TrackerType.getTypes(type), year, true)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addEpisodes(DubStatus.Subbed, episodes)
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        document
            .selectFirst("div.player-embed iframe")
            ?.getIframeAttr()
            ?.let { iframe ->
                loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
            }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (option in mirrorOptions) {
            val base64 = option.attr("value")
            if (base64.isBlank()) continue
            try {
                val decodedHtml = base64Decode(base64.replace("\\s".toRegex(), ""))
                val iframeTag = Jsoup.parse(decodedHtml).selectFirst("iframe")
                val mirrorUrl =
                    when {
                        iframeTag?.attr("src")?.isNotBlank() == true -> iframeTag.attr("src")
                        iframeTag?.attr("data-src")?.isNotBlank() == true -> iframeTag.attr("data-src")
                        else -> null
                    }
                if (!mirrorUrl.isNullOrBlank()) {
                    loadExtractor(httpsify(mirrorUrl), data, subtitleCallback, callback)
                }
            } catch (_: Exception) {
                // Skip broken mirror entries.
            }
        }

        val downloadLinks = document.select("div.dlbox li span.e a[href]")
        for (anchor in downloadLinks) {
            val downloadUrl = anchor.attr("href").trim()
            if (downloadUrl.isNotBlank()) {
                loadExtractor(httpsify(downloadUrl), data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title =
            selectFirst("div.tt")?.text()?.trim()
                ?: selectFirst("a")?.attr("title")?.trim()
                ?: return null
        val cleanTitle =
            title
                .replace(
                    Regex(
                        "\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                ).replace(Regex("\\s+"), " ")
                .trim()
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val type = getType(selectFirst(".typez, .limit .type, span.type")?.text()?.trim())
        val href = fixUrl(link)
        return when (type) {
            TvType.AnimeMovie -> newMovieSearchResponse(cleanTitle, href, type) {
                posterUrl = poster
            }
            else -> newAnimeSearchResponse(cleanTitle, href, type) {
                posterUrl = poster
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("div.tt")?.text()?.trim() ?: return null
        val cleanTitle =
            title
                .replace(
                    Regex(
                        "\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                ).replace(Regex("\\s+"), " ")
                .trim()
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val type = getType(selectFirst(".typez, .limit .type, span.type")?.text()?.trim())
        return when (type) {
            TvType.AnimeMovie -> newMovieSearchResponse(cleanTitle, fixUrl(href), type) {
                this.posterUrl = posterUrl
            }
            else -> newAnimeSearchResponse(cleanTitle, fixUrl(href), type) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun matchesMainPage(sectionName: String, type: TvType): Boolean {
        return when (sectionName) {
            "Movie" -> type == TvType.AnimeMovie
            "TV Series" -> type == TvType.Anime
            "ONA" -> type == TvType.OVA
            else -> true
        }
    }

    private fun buildEpisodes(
        document: org.jsoup.nodes.Document,
        isEpisodePage: Boolean,
    ): List<Episode> {
        val selectors =
            if (isEpisodePage) {
                "#singlepisode .episodelist ul li a[href], div.episodelist ul li a[href], div.eplister ul li a[href]"
            } else {
                "div.eplister ul li a[href], #singlepisode .episodelist ul li a[href]"
            }

        val episodeAnchors = document.select(selectors)
        return episodeAnchors
            .mapNotNull { anchor ->
                val href = fixUrl(anchor.attr("href"))
                if (href.isBlank()) return@mapNotNull null

                val rawTitle =
                    anchor.selectFirst("h3, h4")?.text()?.trim()
                        ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: anchor.text().trim()
                val episodeNumber =
                    Regex("\\b(?:Episode|Eps?|Ep)\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
                        .find(rawTitle)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE)
                            .find(href)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                val name =
                    rawTitle.ifBlank {
                        episodeNumber?.let { "Episode $it" } ?: "Episode"
                    }

                newEpisode(href) {
                    this.name = name
                    this.episode = episodeNumber
                }
            }.distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractSeriesMeta(html: String): SeriesMeta? {
        val match =
            Regex(
                """item":\{"mid":\d+,"cid":\d+,"c":"([^"]+)","s":"([^"]+)","t":"([^"]+)"""",
                RegexOption.IGNORE_CASE,
            ).find(html)
                ?: return null
        return SeriesMeta(
            currentEpisode = match.groupValues[1],
            seriesTitle = match.groupValues[2].unescapeJsString().removeEpisodeSuffix(),
            posterUrl = fixUrlNull(match.groupValues[3].unescapeJsString()),
        )
    }

    private fun String.unescapeJsString(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\\"", "\"")
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(
            Regex("\\s+Episode\\s+\\d+\\b.*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
    }

    private data class SeriesMeta(
        val currentEpisode: String?,
        val seriesTitle: String,
        val posterUrl: String?,
    )

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("data-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }
}
