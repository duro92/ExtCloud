package com.kitanonton

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kitanonton : MainAPI() {
    override var mainUrl = "https://kitanonton2.guru"
    override var name = "Kitanonton"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    private val juicyUserAgent = "Mozilla/5.0"

    override val mainPage =
        mainPageOf(
            "$mainUrl/movies/" to "Movies",
            "$mainUrl/1000-film-terbaik-sepanjang-masa/" to "1000 Film Terbaik",
            "$mainUrl/genre/series-indonesia/" to "Series Indonesia",
            "$mainUrl/genre/drama-korea/" to "Drama Korea",
            "$mainUrl/genre/westseries/" to "West Series",
            "$mainUrl/genre/drama-jepang/" to "Drama Jepang",
            "$mainUrl/genre/thailand-series/" to "Thailand Series",
            "$mainUrl/genre/drama-china/" to "Drama China",
            "$mainUrl/genre/animation/" to "Animation",
        )

    private val episodeDataPrefix = "kitanonton-episode::"
    private val candidateDataPrefix = "kitanonton-link::"
    private val episodeRefererSeparator = "::ref::"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPagedUrl(request.data, page), timeout = 60).document
        val items = document.select("div.movies-list .ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items.distinctBy { it.url }, isHorizontalImages = false)),
            hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", timeout = 60).document
        return document.select("div.movies-list .ml-item").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 60).document
        val isSeries = url.contains("/series/", true)
        val title =
            document.selectFirst("div.mvi-content h3[itemprop=name], div.mvi-content h3")
                ?.ownText()
                ?.trim()
                .orEmpty()
                .ifBlank {
                    document.selectFirst("meta[property=og:title]")?.attr("content")
                        ?.substringAfter("Sub Indo ")
                        ?.substringBefore(" |")
                        ?.trim()
                        .orEmpty()
                }
        val poster =
            fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
                ?: document.selectFirst("div.mvic-thumb, a.mvi-cover")?.extractBackgroundImage()
        val plotParts = document.select("div.desc p").eachText().map { it.trim() }.filter { it.isNotBlank() }
        val plot = plotParts.joinToString("\n").ifBlank { document.selectFirst("div.desc")?.text()?.trim().orEmpty() }
        val tags =
            document.select("div.mvici-left p:contains(Genre:) a, div.mvici-right p:contains(Countries:) a")
                .eachText()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val actors =
            document.select("div.mvici-left p:contains(Actors:) a")
                .eachText()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        val year =
            Regex("""(19|20)\d{2}""")
                .find(
                    document.selectFirst("meta[itemprop=datePublished]")?.attr("content")
                        ?: document.selectFirst("div.mvici-right p:contains(Release Date:)")?.text()
                        ?: title
                )
                ?.value
                ?.toIntOrNull()
        val rating =
            document.selectFirst("span.irank-voters, span.rating")
                ?.text()
                ?.trim()
                ?.toDoubleOrNull()
        val seasonNumber = extractSeasonNumber(title, url)

        return if (isSeries) {
            val watchUrl = "${url.trimEnd('/')}/watch"
            val watchDocument = runCatching { app.get(watchUrl, timeout = 60).document }.getOrNull()
            val episodes = parseEpisodes(watchDocument, watchUrl, seasonNumber)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                if (rating != null) this.score = Score.from10(rating)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "${url.trimEnd('/')}/play") {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                if (rating != null) this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val referer: String
        val candidates: List<String>

        if (data.startsWith(episodeDataPrefix)) {
            val payload = data.removePrefix(episodeDataPrefix)
            val parts = payload.split(episodeRefererSeparator, limit = 2)
            referer =
                parts.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::decodeMetaValue)
                    ?.takeIf { it.startsWith("http") }
                    ?: mainUrl
            candidates =
                parts.getOrNull(1)
                    ?.split("||")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
        } else {
            referer = if (data.startsWith("http")) data else mainUrl
            val document = app.get(data, timeout = 60).document
            candidates = collectServerTargets(document, referer, isSeries = false)
        }

        if (candidates.isEmpty()) return false

        candidates.distinct().forEach { encoded ->
            val (target, targetReferer) = resolveCandidateTarget(encoded, referer) ?: return@forEach
            if (extractJuicyCodes(target, targetReferer, callback)) return@forEach
            runCatching {
                loadExtractor(target, targetReferer, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun parseEpisodes(
        watchDocument: Document?,
        watchUrl: String,
        seasonNumber: Int?,
    ): List<Episode> {
        if (watchDocument == null) return emptyList()

        val episodeLinks = watchDocument.select("#list-eps a.btn-eps[data-iframe], a.btn-eps[data-iframe]")
        if (episodeLinks.isEmpty()) {
            val fallbacks = collectServerTargets(watchDocument, watchUrl, isSeries = true)
            if (fallbacks.isEmpty()) return emptyList()
            return listOf(
                newEpisode(buildEpisodeData(watchUrl, fallbacks)) {
                    name = "Episode 1"
                    season = seasonNumber
                    episode = 1
                }
            )
        }

        val grouped = linkedMapOf<Int, MutableList<String>>()
        episodeLinks.forEach { anchor ->
            val episodeNumber = extractEpisodeNumber(anchor.text(), anchor.id()) ?: return@forEach
            val candidate =
                anchor.toServerTarget(watchUrl, isSeries = true)
                    ?: anchor.attr("data-iframe").trim().takeIf { it.isNotBlank() }
                    ?: return@forEach
            grouped.getOrPut(episodeNumber) { mutableListOf() }.add(candidate)
        }

        return grouped.entries.map { (episodeNumber, encodings) ->
            newEpisode(buildEpisodeData(watchUrl, encodings.distinct())) {
                name = "Episode $episodeNumber"
                season = seasonNumber
                episode = episodeNumber
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun collectEncodedIframes(document: Document): List<String> {
        return document.select("[data-iframe]")
            .mapNotNull { it.attr("data-iframe").trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    private fun collectServerTargets(
        document: Document,
        referer: String,
        isSeries: Boolean,
    ): List<String> {
        val wrappedTargets =
            document.select(".server[onclick], a.btn-eps[onclick]")
                .mapNotNull { it.toServerTarget(referer, isSeries) }
                .distinct()
        return if (wrappedTargets.isNotEmpty()) wrappedTargets else collectEncodedIframes(document)
    }

    private fun decodeIframe(encoded: String): String? {
        return runCatching {
            String(Base64.getDecoder().decode(encoded.trim()))
        }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("//") }
            ?.let { fixUrl(it) }
    }

    private suspend fun resolveCandidateTarget(
        candidate: String,
        fallbackReferer: String,
    ): Pair<String, String>? {
        val decodedCandidate = decodeCandidateData(candidate)
        val rawUrl = decodedCandidate?.first ?: candidate
        val initialReferer = decodedCandidate?.second ?: fallbackReferer
        val directUrl =
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                rawUrl
            } else {
                decodeIframe(rawUrl)
            } ?: return null
        val normalized = normalizeTargetUrl(directUrl, initialReferer) ?: return null

        if (isKitanontonWrapper(normalized)) {
            val wrapperDocument =
                runCatching {
                    app.get(normalized, referer = initialReferer, timeout = 60).document
                }.getOrNull() ?: return null
            val iframeUrl =
                wrapperDocument.selectFirst("iframe[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::fixUrl)
                    ?: return null
            return iframeUrl to normalized
        }

        return normalized to initialReferer
    }

    private suspend fun normalizeTargetUrl(url: String, referer: String): String? {
        if (!url.contains("short.icu", true)) return url
        return runCatching {
            app.get(
                url,
                referer = referer,
                headers = juicyPageHeaders(referer),
                allowRedirects = true,
                timeout = 30
            ).url
        }.getOrNull() ?: url
    }

    private suspend fun extractJuicyCodes(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = runCatching {
            app.get(
                embedUrl,
                referer = referer,
                headers = juicyPageHeaders(referer),
                timeout = 60
            ).text
        }.getOrNull() ?: return false

        if (!page.contains("_juicycodes(", true)) return false

        val payloadExpression =
            Regex("""_juicycodes\(([^<]+)\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(page)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false

        val payload =
            Regex(""""([^"]*)"""")
                .findAll(payloadExpression)
                .joinToString("") { it.groupValues[1] }
                .ifBlank { return false }

        val decodedJs = decodeJuicyPayload(payload) ?: return false
        val sourceBlock =
            Regex(
                """"sources"\s*:\s*\{(.*?)\}\s*,\s*(?:"abouttext"|"sharing")""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(decodedJs)?.groupValues?.getOrNull(1)
                ?: return false

        val streamUrl =
            Regex(""""file"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(sourceBlock)
                ?.groupValues
                ?.getOrNull(1)
                ?.unescapeJsUrl()
                ?.let(::fixUrl)
                ?: return false
        val sourceType =
            Regex(""""type"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(sourceBlock)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.lowercase()
        val isHls = sourceType == "hls" || streamUrl.contains(".m3u8", true)

        val headers =
            mapOf(
                "Referer" to embedUrl,
                "Origin" to getOrigin(embedUrl),
                "Accept" to "*/*",
                "User-Agent" to juicyUserAgent,
            )

        if (isHls) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = headers
                }
            )
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.headers = headers
                }
            )
        }

        return true
    }

    private fun Element.toServerTarget(referer: String, isSeries: Boolean): String? {
        val onclick = attr("onclick").trim()
        val match =
            Regex("""load_(?:movie|episode)_iframe\((\d+),\s*(\d+)\)""", RegexOption.IGNORE_CASE)
                .find(onclick)
                ?: return null
        val serverType = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val source =
            when (serverType) {
                10, 8 -> attr("data-drive")
                7 -> attr("data-mp4")
                6 -> attr("data-strgo")
                2 -> attr("data-iframe")
                1 -> attr("data-openload")
                else -> attr("data-iframe")
            }.trim().takeIf { it.isNotBlank() } ?: return null

        val wrapperBase =
            when (serverType) {
                10 -> if (isSeries) "$mainUrl/googledrive/?type=series&source=" else "$mainUrl/googledrive/?source="
                8 -> if (isSeries) "$mainUrl/gdrive/?type=series&url=" else "$mainUrl/gdrive/?url="
                7 -> if (isSeries) "$mainUrl/player/?type=series&source=" else "$mainUrl/player/?source="
                6 -> if (isSeries) "$mainUrl/stremagoembed/?type=series&source=" else "$mainUrl/stremagoembed/?source="
                2 -> "$mainUrl/iembed/?source="
                1 -> if (isSeries) "$mainUrl/openloadembed/?type=series&source=" else "$mainUrl/openloadembed/?source="
                else -> null
            } ?: return null

        return encodeCandidateData(wrapperBase + source, referer)
    }

    private fun decodeJuicyPayload(payload: String): String? {
        if (payload.length < 4) return null

        val salt =
            payload.takeLast(3)
                .map { (it.code - 100).toString() }
                .joinToString("")
                .toIntOrNull()
                ?: return null

        val body = payload.dropLast(3)
        val normalized = body.replace('_', '+').replace('-', '/').padBase64()
        val step =
            runCatching { String(Base64.getDecoder().decode(normalized)) }
                .getOrNull()
                ?: return null

        val symbols = listOf('`', '%', '-', '+', '*', '$', '!', '_', '^', '=')
        val digits =
            buildString {
                step.forEach { ch ->
                    val idx = symbols.indexOf(ch)
                    if (idx >= 0) append(idx)
                }
            }

        if (digits.length < 4) return null

        return buildString {
            digits.chunked(4).forEach { chunk ->
                if (chunk.length == 4) {
                    val code = (chunk.toIntOrNull()?.rem(1000) ?: return@forEach) - salt
                    append(code.toChar())
                }
            }
        }
    }

    private fun buildEpisodeData(referer: String, encodings: List<String>): String {
        return episodeDataPrefix +
            encodeMetaValue(referer) +
            episodeRefererSeparator +
            encodings.joinToString("||")
    }

    private fun encodeCandidateData(url: String, referer: String): String {
        return candidateDataPrefix +
            encodeMetaValue(url) +
            episodeRefererSeparator +
            encodeMetaValue(referer)
    }

    private fun decodeCandidateData(value: String): Pair<String, String>? {
        if (!value.startsWith(candidateDataPrefix)) return null
        val payload = value.removePrefix(candidateDataPrefix)
        val parts = payload.split(episodeRefererSeparator, limit = 2)
        val url = parts.firstOrNull()?.let(::decodeMetaValue)?.takeIf { it.startsWith("http") } ?: return null
        val referer = parts.getOrNull(1)?.let(::decodeMetaValue)?.takeIf { it.startsWith("http") } ?: mainUrl
        return url to referer
    }

    private fun encodeMetaValue(value: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    }

    private fun decodeMetaValue(value: String): String {
        return runCatching {
            String(Base64.getUrlDecoder().decode(value))
        }.getOrDefault(value)
    }

    private fun String.padBase64(): String {
        val pad = (4 - (length % 4)) % 4
        return this + "=".repeat(pad)
    }

    private fun String.unescapeJsUrl(): String {
        return replace("\\/", "/")
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        val base = url.trimEnd('/')
        return if (page <= 1) "$base/" else "$base/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (document.selectFirst("link[rel=next]") != null) return true
        return document.select("#pagination a[href], nav a[href]")
            .any { it.attr("href").contains("/page/${page + 1}/") }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.ml-mask[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title =
            anchor.attr("title").trim().ifBlank {
                selectFirst("span.mli-info h2")?.text()?.trim().orEmpty()
            }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.getImageAttr()
        val quality = getQualityFromString(selectFirst(".mli-quality")?.text().orEmpty())
        val isSeries = href.contains("/series/", true) || selectFirst(".mli-eps") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    private fun Element.getImageAttr(): String? {
        val raw =
            when {
                hasAttr("data-original") -> attr("abs:data-original")
                hasAttr("data-src") -> attr("abs:data-src")
                hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
                hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
                else -> attr("abs:src")
            }
        val cleaned = raw.trim()
        return if (cleaned.isBlank()) null else fixUrlNull(cleaned)
    }

    private fun Element.extractBackgroundImage(): String? {
        val style = attr("style")
        val value =
            Regex("""url\((['"]?)(.*?)\1\)""")
                .find(style)
                ?.groupValues
                ?.getOrNull(2)
                ?.trim()
        return fixUrlNull(value)
    }

    private fun extractSeasonNumber(title: String, url: String): Int? {
        return (
            Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""season-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String, fallback: String): Int? {
        return (
            Regex("""Ep(?:isode)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""episode-\d+(\d+)$""", RegexOption.IGNORE_CASE)
                    .find(fallback)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: Regex("""(\d+)""").find(text)?.groupValues?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun getOrigin(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun isKitanontonWrapper(url: String): Boolean {
        if (!url.startsWith(mainUrl, true)) return false
        return listOf("/iembed/", "/player/", "/gdrive/", "/googledrive/", "/openloadembed/", "/stremagoembed/")
            .any { url.contains(it, true) }
    }

    private fun juicyPageHeaders(referer: String): Map<String, String> {
        return mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer,
            "User-Agent" to juicyUserAgent,
        )
    }
}
