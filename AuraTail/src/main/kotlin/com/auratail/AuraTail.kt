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
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru Ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
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
        val poster =
            document
                .selectFirst("div.bigcontent img")
                ?.getImageAttr()
                ?.let { fixUrlNull(it) }

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
        val type = getType(typeText)

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

        val episodeElements = document.select("div.eplister ul li a")
        val episodes =
            episodeElements
                .reversed()
                .mapIndexed { index, anchor ->
                    val href = fixUrl(anchor.attr("href"))
                    newEpisode(href) {
                        name = "Episode ${index + 1}"
                        episode = index + 1
                    }
                }

        val altTitles =
            listOfNotNull(
                title,
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
        val typeText = selectFirst(".typez, .limit .type, span.type")?.text()?.trim()
        return newAnimeSearchResponse(cleanTitle, fixUrl(link), getType(typeText)) {
            posterUrl = poster
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
        val typeText = selectFirst(".typez, .limit .type, span.type")?.text()?.trim()
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), getType(typeText)) {
            this.posterUrl = posterUrl
        }
    }

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
