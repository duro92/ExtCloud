package com.anoboy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    override var mainUrl = "https://ww1.anoboy.boo"
    override var name = "Anoboy⚡"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime
    )

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "anime/" to "Baru Ditambahkan",
        "anime/ongoing/" to "Ongoing",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimStart('/')
        val url = if (page <= 1) {
            "$mainUrl/$path"
        } else {
            "$mainUrl/$path" + "page/$page/"
        }
        val document = app.get(url).document
        val items = document.select("div.column-content > a[href]:has(div.amv)")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = attr("href").ifBlank { selectFirst("a[href]")?.attr("href").orEmpty() }
        if (link.isBlank()) return null

        val title = attr("title").trim().ifBlank {
            selectFirst("h3.ibox1")?.text()?.trim().orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val isMovie = link.contains("/anime-movie/", true) || title.contains("movie", true)
        val isOva = title.contains("ova", true) || title.contains("special", true)
        if (isMovie || isOva) return null
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, fixUrl(link), tvType) {
            posterUrl = poster
        }
    }

    private fun Element.toLegacySearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst("div.tt")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim()
            ?: return null
        val isMovie = link.contains("/anime-movie/", true) || title.contains("movie", true)
        val isOva = title.contains("ova", true) || title.contains("special", true)
        if (isMovie || isOva) return null
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        val modernResults = document.select("div.column-content > a[href]:has(div.amv)")
            .mapNotNull { it.toSearchResult() }
        if (modernResults.isNotEmpty()) return modernResults
        return document.select("div.listupd article.bs")
            .mapNotNull { it.toLegacySearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val href = if (tagName() == "a") {
            attr("href")
        } else {
            selectFirst("a[href]")?.attr("href").orEmpty()
        }
        if (href.isBlank()) return null

        val title = selectFirst("h3.ibox1")?.text()?.trim()
            ?: selectFirst("div.tt")?.text()?.trim()
            ?: attr("title").trim().ifBlank { null }
            ?: return null

        val isMovie = href.contains("/anime-movie/", true) || title.contains("movie", true)
        val isOva = title.contains("ova", true) || title.contains("special", true)
        if (isMovie || isOva) return null
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        val posterUrl = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim().orEmpty()
        val poster = document
            .selectFirst("div.column-three-fourth > img, div.column-content > img, div.bigcontent img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val description = (
            document.selectFirst("div.unduhan:not(:has(table))")
                ?.text()
                ?.trim()
                ?.ifBlank { null }
                ?: document.select("div.entry-content p").joinToString("\n") { it.text() }
            )
            .trim()

        val tableRows = document.select("div.unduhan table tr")
        fun getTableValue(label: String): String? {
            return tableRows.firstOrNull {
                it.selectFirst("th")?.text()?.contains(label, true) == true
            }?.selectFirst("td")?.text()?.trim()
        }

        val year = Regex("/(20\\d{2})/")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val duration = getTableValue("Durasi")?.let { text ->
            val hours = Regex("(\\d+)\\s*(jam|hr)", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val minutes = Regex("(\\d+)\\s*(menit|min)", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            hours * 60 + minutes
        }

        val tags = getTableValue("Genre")
            ?.split(",", "/")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val actors = emptyList<String>()
        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()
            ?: getTableValue("Score")?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe, iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"]")
            ?.attr("src")
        val status = getStatus(getTableValue("Status"))

        val recommendations = document.select("div.column-content > a[href]:has(div.amv), div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }

        val castList = emptyList<ActorData>()

        val episodeElements = document.select("div.singlelink ul.lcp_catlist li a, div.eplister ul li a")
        val seasonHeaders = document.select("div.hq")

        fun normalizeTitle(raw: String): String {
            var titleText = raw.trim()
            titleText = titleText.replace("\\[(Streaming|Download)\\]".toRegex(RegexOption.IGNORE_CASE), "")
            titleText = titleText.replace("(Streaming|Download)".toRegex(RegexOption.IGNORE_CASE), "")
            return titleText.trim()
        }

        fun filterStreamingIfAvailable(elements: List<Element>): List<Element> {
            val hasStreamingOrDownload = elements.any { anchor ->
                val text = anchor.text()
                val href = anchor.attr("href")
                text.contains("streaming", true) ||
                    text.contains("download", true) ||
                    href.contains("streaming", true) ||
                    href.contains("download", true)
            }
            return if (hasStreamingOrDownload) {
                elements.filter { anchor ->
                    val text = anchor.text()
                    val href = anchor.attr("href")
                    text.contains("streaming", true) || href.contains("streaming", true)
                }
            } else {
                elements
            }
        }

        val seasonGroups = buildList {
            for (header in seasonHeaders) {
                val seasonNum = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(header.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                var sibling = header.nextElementSibling()
                while (sibling != null &&
                    !sibling.hasClass("singlelink") &&
                    !sibling.hasClass("eplister")
                ) {
                    sibling = sibling.nextElementSibling()
                }
                val anchors = sibling
                    ?.select("ul.lcp_catlist li a, ul li a")
                    ?.toList()
                    ?: emptyList()
                if (anchors.isNotEmpty()) {
                    add(seasonNum to anchors)
                }
            }
        }

        val groupedElements = if (seasonGroups.isNotEmpty()) {
            seasonGroups.flatMap { (seasonNum, anchors) ->
                filterStreamingIfAvailable(anchors).map { seasonNum to it }
            }
        } else {
            filterStreamingIfAvailable(episodeElements.toList()).map { null to it }
        }

        val episodes = groupedElements
            .reversed()
            .mapIndexed { index, (seasonNum, aTag) ->
                val href = fixUrl(aTag.attr("href"))
                val rawTitle = aTag.text().trim()
                val cleanedTitle = normalizeTitle(rawTitle)
                val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(rawTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: Regex("episode[-\\s]?(\\d+)", RegexOption.IGNORE_CASE)
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                    ?: if (seasonNum != null && !rawTitle.contains("Episode", true)) 1 else (index + 1)

                newEpisode(href) {
                    name = if (cleanedTitle.isBlank()) "Episode $episodeNumber" else cleanedTitle
                    episode = episodeNumber
                    if (seasonNum != null) this.season = seasonNum
                }
            }

        fun isValidEpisodeUrl(raw: String?): Boolean {
            val clean = raw?.trim().orEmpty()
            return clean.isNotBlank() &&
                clean != "#" &&
                !clean.equals("none", true) &&
                !clean.startsWith("javascript", true)
        }

        fun buildServerEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
            val serverGroups = doc.select("div.satu, div.dua, div.tiga, div.empat, div.lima, div.enam")
            val anchors = serverGroups.flatMap { group -> group.select("a[data-video]") }
            val fallbackAnchors = if (anchors.isNotEmpty()) anchors else doc.select("a[data-video]")
            if (fallbackAnchors.isEmpty()) return emptyList()

            val episodesByNumber = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
            fallbackAnchors.forEachIndexed { index, anchor ->
                val dataVideo = anchor.attr("data-video").ifBlank { anchor.attr("href") }
                if (!isValidEpisodeUrl(dataVideo)) return@forEachIndexed

                val rawTitle = anchor.text().trim()
                val episodeNumber = Regex("(\\d+)")
                    .find(rawTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: (index + 1)
                val resolvedUrl = fixUrl(dataVideo)
                val cleanedTitle = normalizeTitle(rawTitle)

                episodesByNumber
                    .getOrPut(episodeNumber) { mutableListOf() }
                    .add(resolvedUrl to cleanedTitle)
            }

            return episodesByNumber
                .toSortedMap()
                .mapNotNull { (episodeNumber, entries) ->
                    val urls = entries.map { it.first }.distinct()
                    val title = entries.map { it.second }.firstOrNull { it.isNotBlank() }
                        ?: "Episode $episodeNumber"
                    if (urls.isEmpty()) return@mapNotNull null

                    val data = if (urls.size == 1) urls.first() else {
                        "multi::" + urls.joinToString("||")
                    }

                    newEpisode(data) {
                        name = title
                        episode = episodeNumber
                    }
                }
        }

        val serverEpisodes = buildServerEpisodes(document)
        val useServerEpisodes = seasonHeaders.isEmpty() && serverEpisodes.isNotEmpty() && episodes.size <= 1
        val finalEpisodes = if (useServerEpisodes) serverEpisodes else episodes

        val altTitles = listOfNotNull(
            title,
            document.selectFirst("span:matchesOwn(Judul Inggris:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Jepang:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Asli:)")?.ownText()?.trim(),
        ).distinct()

        val malIdFromPage = document.selectFirst("a[href*=\"myanimelist.net/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()
        val aniIdFromPage = document.selectFirst("a[href*=\"anilist.co/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()

        val defaultType = if (url.contains("/anime-movie/", true)) TvType.AnimeMovie else TvType.Anime
        val parsedType = getType(getTableValue("Tipe"))
        val type = if (episodes.isNotEmpty()) {
            TvType.Anime
        } else if (defaultType == TvType.AnimeMovie) {
            TvType.AnimeMovie
        } else {
            parsedType
        }

        val tracker = APIHolder.getTracker(altTitles, TrackerType.getTypes(type), year, true)

        return if (finalEpisodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addEpisodes(DubStatus.Subbed, finalEpisodes)
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
                this.plot = description
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val multiPrefix = "multi::"
        val isMulti = data.startsWith(multiPrefix)
        val document = if (isMulti) null else app.get(data).document
        val discoveredUrls = linkedSetOf<String>()
        val queuedUrls = ArrayDeque<String>()
        val crawledUrls = mutableSetOf<String>()

        fun isValidUrl(raw: String?): Boolean {
            val clean = raw?.trim().orEmpty()
            return clean.isNotBlank() &&
                clean != "#" &&
                !clean.equals("none", true) &&
                !clean.startsWith("javascript", true)
        }

        fun resolveUrl(raw: String?, base: String): String? {
            if (!isValidUrl(raw)) return null
            val clean = raw!!.trim()
            return try {
                when {
                    clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                    clean.startsWith("//") -> "https:$clean"
                    else -> URI(base).resolve(clean).toString()
                }
            } catch (_: Exception) {
                try {
                    fixUrl(clean)
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun queueUrl(raw: String?, base: String) {
            val resolved = resolveUrl(raw, base) ?: return
            if (discoveredUrls.add(resolved)) queuedUrls.add(resolved)
        }

        fun extractFromDoc(baseUrl: String, doc: org.jsoup.nodes.Document) {
            doc.select("iframe#mediaplayer, iframe#videoembed, div.player-embed iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                .forEach { queueUrl(it.getIframeAttr(), baseUrl) }

            doc.select("a[href*=\"yourupload.com/embed/\"], a[href*=\"yourupload.com/watch/\"], a[href*=\"www.yourupload.com/embed/\"], a[href*=\"www.yourupload.com/watch/\"]")
                .forEach { queueUrl(it.attr("href"), baseUrl) }

            doc.select("#fplay a#allmiror[data-video], #fplay a[data-video], a#allmiror[data-video], a[data-video], [data-video]")
                .forEach { anchor ->
                    queueUrl(anchor.attr("data-video"), baseUrl)
                    queueUrl(anchor.attr("href"), baseUrl)
                }

            doc.select("[data-embed], [data-iframe], [data-url], [data-src]")
                .forEach { el ->
                    queueUrl(el.attr("data-embed"), baseUrl)
                    queueUrl(el.attr("data-iframe"), baseUrl)
                    queueUrl(el.attr("data-url"), baseUrl)
                    queueUrl(el.attr("data-src"), baseUrl)
                }

            doc.select("div.download a.udl[href], div.download a[href], div.dlbox li span.e a[href]")
                .forEach { queueUrl(it.attr("href"), baseUrl) }

            val bloggerRegex = Regex("""https?://(?:www\.)?blogger\.com/video\.g\?[^"'<\s]+""", RegexOption.IGNORE_CASE)
            val batchRegex = Regex("""/uploads/(?:adsbatch[^"'\s]+|yupbatch[^"'\s]+)""", RegexOption.IGNORE_CASE)
            val yourUploadRegex = Regex("""https?://(?:www\.)?yourupload\.com/(?:embed|watch)/[^"'<\s]+""", RegexOption.IGNORE_CASE)
            doc.select("script").forEach { script ->
                val scriptData = script.data()
                bloggerRegex.findAll(scriptData).forEach { match ->
                    queueUrl(match.value, baseUrl)
                }
                batchRegex.findAll(scriptData).forEach { match ->
                    queueUrl(match.value, baseUrl)
                }
                yourUploadRegex.findAll(scriptData).forEach { match ->
                    queueUrl(match.value, baseUrl)
                }
            }
        }

        fun shouldCrawl(url: String): Boolean {
            val lower = url.lowercase()
            if (lower.contains("blogger.com/video.g")) return false
            if (lower.endsWith(".mp4") || lower.endsWith(".m3u8")) return false
            return lower.contains("anoboy.boo") ||
                lower.contains("/uploads/") ||
                lower.contains("adsbatch") ||
                lower.contains("yupbatch")
        }

        if (document != null) {
            extractFromDoc(data, document)
        }
        if (isMulti) {
            data.removePrefix(multiPrefix)
                .split("||")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { queueUrl(it, mainUrl) }
        }

        var safety = 0
        while (queuedUrls.isNotEmpty() && safety++ < 120) {
            val next = queuedUrls.removeFirst()
            if (!shouldCrawl(next) || !crawledUrls.add(next)) continue
            try {
                val nestedDoc = app.get(next, referer = data).document
                extractFromDoc(next, nestedDoc)
            } catch (_: Exception) {
                // skip dead mirror page
            }
        }

        if (discoveredUrls.isEmpty() && document != null) {
            // fallback for old mirrored options stored as base64 iframe html
            val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
            for (opt in mirrorOptions) {
                val base64 = opt.attr("value")
                if (base64.isBlank()) continue
                try {
                    val decodedHtml = base64Decode(base64.replace("\\s".toRegex(), ""))
                    Jsoup.parse(decodedHtml).selectFirst("iframe")?.getIframeAttr()?.let { iframe ->
                        queueUrl(iframe, data)
                    }
                } catch (_: Exception) {
                    // ignore broken base64 mirrors
                }
            }
        }

        val bloggerLinks = discoveredUrls.filter {
            it.contains("blogger.com/video.g", true) ||
                it.contains("blogger.googleusercontent.com", true)
        }

        val fallbackLinks = discoveredUrls.filterNot {
            it.contains("blogger.com/video.g", true) ||
                it.contains("blogger.googleusercontent.com", true)
        }

        var foundLinks = 0
        val callbackWrapper: (ExtractorLink) -> Unit = { link ->
            foundLinks++
            callback(link)
        }

        // Try Blogger first, then continue with all other mirrors so users can switch sources.
        bloggerLinks.distinct().forEach { link ->
            loadExtractor(link, data, subtitleCallback, callbackWrapper)
        }
        fallbackLinks.distinct().forEach { link ->
            loadExtractor(link, data, subtitleCallback, callbackWrapper)
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        val result = when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
        return if (result.isBlank()) attr("src").substringBefore(" ") else result
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("data-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }
}
