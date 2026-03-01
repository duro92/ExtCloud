package com.nontonanimeid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Nontonanimeid : MainAPI() {
    override var mainUrl = "https://s11.nontonanimeid.boats"
    override var name = "Nontonanimeid"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "anime/?mode=&sort=series_tahun_newest&status=&type=" to "Terbaru",
        "anime/?mode=&sort=series_popularity&status=&type=" to "Terpopuler",
        "anime/?mode=&sort=series_skor&status=&type=" to "Rating Tertinggi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = buildPagedUrl(request.data, page)
        val response = app.get(targetUrl)
        mainUrl = getBaseUrl(response.url)
        val document = response.document

        val items = document.select("a.as-anime-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get("$mainUrl/?s=$encoded")
        mainUrl = getBaseUrl(response.url)
        val document = response.document

        return document.select("a.as-anime-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val pageUrl = response.url
        mainUrl = getBaseUrl(pageUrl)
        val document = response.document

        val title = cleanTitle(
            document.selectFirst("h1.entry-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "NontonAnimeID"
        )

        val poster = document.selectFirst(".anime-card__sidebar img, .anime-card img")
            ?.getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.select("div.synopsis-prose p")
            .joinToString("\n") { it.text().trim() }
            .ifBlank {
                document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            }
            .ifBlank { null }

        val score = document.selectFirst(".anime-card__score, .score-value")
            ?.text()
            ?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val year = document.select("ul.details-list li")
            .firstOrNull { it.text().contains("Aired:", true) }
            ?.text()
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value }
            ?.toIntOrNull()

        val type = getType(
            document.selectFirst(".anime-card__quick-info .type, .anime-card .type")
                ?.text()
        )

        val status = getStatus(
            document.selectFirst(".anime-card__quick-info .info-item[class*=status]")
                ?.text()
        )

        val tags = document.select(".anime-card__genres .genre-tag")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(".related a.as-anime-card")
            .mapNotNull { it.toSearchResult() }
            .filterNot { fixUrl(it.url) == fixUrl(pageUrl) }
            .distinctBy { it.url }

        val episodes = document.select(".episode-list-items a.episode-item")
            .mapNotNull { element ->
                val epUrl = element.attr("href").ifBlank { element.attr("data-episode-url") }
                if (epUrl.isBlank()) return@mapNotNull null
                val epNumber = extractEpisodeNumber(element)
                Pair(epNumber, fixUrl(epUrl))
            }
            .distinctBy { it.second }
            .sortedBy { it.first ?: Int.MAX_VALUE }
            .map { (epNumber, epUrl) ->
                newEpisode(epUrl) {
                    this.name = if (epNumber != null) "Episode $epNumber" else null
                    this.episode = epNumber
                }
            }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, pageUrl, type) {
                posterUrl = poster
                this.plot = description
                this.tags = tags
                showStatus = status
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                score?.let { addScore(it.toString(), 10) }
                this.recommendations = recommendations
            }
        } else {
            val movieType = when (type) {
                TvType.OVA -> TvType.OVA
                else -> TvType.AnimeMovie
            }
            newMovieLoadResponse(title, pageUrl, movieType, pageUrl) {
                posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                score?.let { addScore(it.toString(), 10) }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val pageUrl = response.url
        mainUrl = getBaseUrl(pageUrl)
        val document = response.document

        val emitted = hashSetOf<String>()
        var found = false

        suspend fun emitExtractor(raw: String?, referer: String = pageUrl) {
            val candidate = raw?.trim().orEmpty()
            if (candidate.isBlank()) return
            val fixed = fixUrl(httpsify(candidate))
            if (!emitted.add(fixed)) return

            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) {
                    found = true
                    callback.invoke(it)
                }
            }
        }

        suspend fun emitFromHtml(html: String, referer: String = pageUrl) {
            val doc = Jsoup.parse(html, referer)
            doc.select("iframe").forEach { emitExtractor(it.getIframeAttr(), referer) }
            doc.select("video[src], source[src]").forEach { emitExtractor(it.attr("src"), referer) }
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http", true) || href.startsWith("//")) {
                    emitExtractor(href, referer)
                }
            }
        }

        // Already-loaded iframe/source on page
        document.select("#videoku iframe, #videoku video[src], #videoku source[src], .player-embed iframe, iframe.lazy")
            .forEach { element ->
                val source = when (element.tagName().lowercase()) {
                    "iframe" -> element.getIframeAttr()
                    else -> element.attr("src")
                }
                emitExtractor(source, pageUrl)
            }

        val ajaxConfig = extractKotakAjaxConfig(document)
        val ajaxUrl = ajaxConfig?.url?.ifBlank { null } ?: "$mainUrl/wp-admin/admin-ajax.php"
        val nonce = ajaxConfig?.nonce.orEmpty()

        for (server in document.select("li.serverplayer[data-post][data-nume][data-type]")) {
            val postId = server.attr("data-post").trim()
            val nume = server.attr("data-nume").trim()
            val serverName = server.attr("data-type").trim().lowercase()
            if (postId.isBlank() || nume.isBlank()) continue

            val ajaxResponse = runCatching {
                app.post(
                    url = ajaxUrl,
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "serverName" to serverName,
                        "nonce" to nonce,
                    ),
                    referer = pageUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            }.getOrNull() ?: continue

            emitFromHtml(ajaxResponse, pageUrl)
        }

        if (!found) {
            // Fallback direct links on episode page
            document.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.contains("kotak", true) || href.contains("nonton", true)) {
                    emitExtractor(href, pageUrl)
                }
            }
        }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank()) return null

        val title = selectFirst(".as-anime-title")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: attr("title").trim().ifBlank { null }
            ?: return null

        val typeText = selectFirst(".as-type")?.text()?.trim()
        val tvType = getType(typeText)
        val posterUrl = selectFirst("img")?.getImageAttr()
            ?: extractPosterFromStyle(attr("style"))

        return newAnimeSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = posterUrl
        }
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val cleaned = path.trimStart('/')
        val basePath = cleaned.substringBefore("?").trimEnd('/')
        val query = cleaned.substringAfter("?", "").trim()

        val pagedPath = if (page <= 1) "$basePath/" else "$basePath/page/$page/"
        return if (query.isBlank()) "$mainUrl/$pagedPath" else "$mainUrl/$pagedPath?$query"
    }

    private fun cleanTitle(text: String): String {
        return text
            .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Sub\\s+Indo.*$", RegexOption.IGNORE_CASE), "")
            .replace("- Nonton Anime ID", "", ignoreCase = true)
            .trim()
    }

    private fun getBaseUrl(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}"
    }

    private fun extractPosterFromStyle(style: String?): String? {
        val raw = style?.let { Regex("url\\(['\"]?([^'\")]+)").find(it)?.groupValues?.getOrNull(1) }
        return raw?.let { fixUrl(it) }
    }

    private fun extractEpisodeNumber(element: Element): Int? {
        val href = element.attr("href")
        val fromHref = Regex("episode-(\\d+)", RegexOption.IGNORE_CASE)
            .find(href)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (fromHref != null) return fromHref

        return Regex("(\\d+)").find(element.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun getType(text: String?): TvType {
        val value = text?.trim().orEmpty()
        return when {
            value.contains("movie", true) -> TvType.AnimeMovie
            value.contains("special", true) -> TvType.OVA
            value.contains("ova", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(text: String?): ShowStatus? {
        val value = text?.trim().orEmpty()
        return when {
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("finished", true) -> ShowStatus.Completed
            value.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractKotakAjaxConfig(document: org.jsoup.nodes.Document): KotakAjaxConfig? {
        val encodedScript = document.selectFirst("script#ajax_video-js-extra")
            ?.attr("src")
            ?.substringAfter("base64,", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val decoded = runCatching { base64Decode(encodedScript) }.getOrNull() ?: return null
        val objectText = Regex("var\\s+kotakajax\\s*=\\s*(\\{.*})")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.trimEnd(';')
            ?: return null

        return tryParseJson<KotakAjaxConfig>(objectText)
            ?: tryParseJson<KotakAjaxConfig>(objectText.replace("\\/", "/"))
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    data class KotakAjaxConfig(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("nonce") val nonce: String? = null,
        @JsonProperty("autoload") val autoload: String? = null,
        @JsonProperty("serverNames") val serverNames: List<String>? = null,
    )
}
