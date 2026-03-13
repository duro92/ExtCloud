package com.pmsm

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Pmsm : MainAPI() {
    override var mainUrl = "https://ww192.pencurimoviesubmalay.motorcycles"
    override var name = "PMSM😁"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "tvshows" to "TV Shows",
        "group_movie/indonesia" to "Indonesia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = buildPagedUrl(request.data, page)
        val document = app.get(targetUrl, timeout = 60).document

        val items = document.select("div.display-item div.item-box")
            .mapNotNull { it.toItemBoxSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNextPage(document, page)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", timeout = 60).document

        val list = document.select("div.display-item div.item-box")
            .mapNotNull { it.toItemBoxSearchResult() } +
            document.select("div.module-item")
                .mapNotNull { it.toModuleSearchResult() }

        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 60).document
        val isSeries = url.contains("/tvshows/")

        val rawTitle = document.selectFirst("div.details-title h3")?.text()?.trim().orEmpty()
        val title = if (isSeries) rawTitle else rawTitle.substringBeforeLast("(").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.substringBefore("\r")
                ?.trim()
        )
        val description = document.selectFirst("div.details-desc p")?.text()?.trim()
        val tags = document.select("div.details-genre a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document.select("div.details-info p:contains(Stars) a").map { it.text().trim() }
            .filter { it.isNotBlank() }
        val year = extractYear(document.selectFirst("div.details-info p:contains(Year)")?.text() ?: rawTitle)
        val duration = document.selectFirst("span[itemprop=duration]")?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()
        val rating = Regex("""(\d+(\.\d+)?)""")
            .find(document.selectFirst("span.data-imdb")?.text().orEmpty())
            ?.groupValues
            ?.firstOrNull()
            ?.toDoubleOrNull()
        val trailerId = document.selectFirst("span.data-trailer[data-tid], a.btn-trailer[data-tid]")
            ?.attr("data-tid")
            ?.trim()
        val trailerUrl = trailerId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }

        val recommendations = document.select("div.module-item")
            .mapNotNull { it.toModuleSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (isSeries) {
            val episodes = document.select("div.content-episodes ul.episodes-list li")
                .mapNotNull { it.toEpisode() }
                .distinctBy { it.data }
                .sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (duration != null) this.duration = duration
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var pageUrl = data
        var document = app.get(pageUrl, timeout = 60).document
        var options = document.getServerOptions()

        // If user opens a TV Show page directly, jump to first episode page for sources.
        if (options.isEmpty() && pageUrl.contains("/tvshows/")) {
            val firstEpisode = document.selectFirst("div.content-episodes ul.episodes-list li a[href]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            if (firstEpisode != null && firstEpisode != pageUrl) {
                pageUrl = firstEpisode
                document = app.get(pageUrl, timeout = 60).document
                options = document.getServerOptions()
            }
        }

        val emitted = mutableSetOf<String>()
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) callback(link)
        }

        suspend fun emitExtractor(embedUrl: String) {
            val fixed = fixUrl(embedUrl.replace("\\/", "/").trim())
            if (fixed.isBlank()) return
            if (fixed.contains("youtube", true) || fixed.contains("youtu.be", true)) return

            val before = emitted.size
            runCatching { loadExtractor(fixed, pageUrl, subtitleCallback, wrappedCallback) }

            // Fallback parser for larhu embeds (JWPlayer with direct m3u8 in page source).
            if (emitted.size == before && fixed.contains("larhu.website", true)) {
                extractLarhuStreams(fixed, wrappedCallback)
            }
        }

        document.select("div.display-video iframe[src], iframe.metaframe[src], div#display-noajax iframe[src]")
            .mapNotNull { it.attr("src").takeIf(String::isNotBlank) }
            .forEach { emitExtractor(it) }

        options.forEach { opt ->
            if (opt.nume.equals("fake", true)) return@forEach

            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "zeta_player_ajax",
                        "post" to opt.post,
                        "nume" to opt.nume,
                        "type" to opt.type
                    ),
                    referer = pageUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ZetaPlayerResponse>()
            }.getOrNull() ?: return@forEach

            val embedRaw = response.embedUrl ?: return@forEach
            if (response.type.equals("ztshcode", true)) {
                val token = decodeZtshcodeToken(embedRaw)
                if (!token.isNullOrBlank()) {
                    emitExtractor("https://yandexcdn.com/f/$token")
                    emitExtractor("https://yandexcdn.com/e/$token")
                    return@forEach
                }
            }
            val embedUrl = extractEmbedUrl(embedRaw) ?: return@forEach
            emitExtractor(embedUrl)
        }

        return emitted.isNotEmpty()
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val clean = path.trim('/').trim()
        return if (page <= 1) "$mainUrl/$clean/" else "$mainUrl/$clean/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val totalText = document.selectFirst("div.page-nav .pagination span.total")?.text().orEmpty()
        val totalPages = Regex("""of\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(totalText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (totalPages != null) return page < totalPages

        return document.select("div.page-nav .pagination a[href]")
            .any { it.attr("href").contains("/page/${page + 1}/") }
    }

    private fun Element.toItemBoxSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val ptype = link.attr("data-ptype").lowercase()
        val tvType = when {
            ptype.contains("tv") -> TvType.TvSeries
            href.contains("/tvshows/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val title = selectFirst("div.item-desc-title h3")?.text()?.trim()
            ?: link.attr("title").trim()
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let {
            fixUrlNull(it.attr("data-original").ifBlank { it.attr("src") })
        }
        val quality = getQualityFromString(selectFirst("span.item-quality")?.text().orEmpty())

        return buildSearchResponse(title, href, tvType, poster, quality)
    }

    private fun Element.toModuleSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        if (!href.contains("/movies/") && !href.contains("/tvshows/")) return null

        val tvType = if (href.contains("/tvshows/")) TvType.TvSeries else TvType.Movie
        val title = link.attr("title").trim().ifBlank {
            selectFirst("h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let {
            fixUrlNull(it.attr("data-original").ifBlank { it.attr("src") })
        }

        return buildSearchResponse(title, href, tvType, poster, null)
    }

    private fun buildSearchResponse(
        title: String,
        href: String,
        tvType: TvType,
        poster: String?,
        quality: SearchQuality?
    ): SearchResponse {
        return if (tvType == TvType.TvSeries) {
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

    private fun Element.toEpisode(): Episode? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val epNum = selectFirst("span.ep-num")?.text()?.trim()?.toIntOrNull()
        val title = selectFirst("span.ep-title")?.text()?.trim()
        val poster = selectFirst("span.ep-thumb img")?.attr("src")?.let { fixUrlNull(it) }

        val classSeason = classNames()
            .firstNotNullOfOrNull { Regex("""ep-(\d+)-\d+""").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
        val parentSeason = parent()?.id()
            ?.substringAfter("season-listep-", "")
            ?.toIntOrNull()
        val season = classSeason ?: parentSeason

        return newEpisode(href) {
            this.name = title
            this.posterUrl = poster
            this.episode = epNum
            this.season = season
        }
    }

    private fun Document.getServerOptions(): List<ZetaOption> {
        val defaultType = if (location().contains("/episodes/")) "ep" else "mv"
        return select("ul#playeroptionsul li.zetaflix_player_option[data-post][data-nume]")
            .mapNotNull { li ->
                val post = li.attr("data-post").trim()
                val nume = li.attr("data-nume").trim()
                val type = li.attr("data-type").trim().ifBlank { defaultType }
                if (post.isBlank() || nume.isBlank()) return@mapNotNull null
                ZetaOption(post, nume, type)
            }
            .distinct()
    }

    private fun extractEmbedUrl(raw: String): String? {
        val cleaned = raw.replace("\\/", "/").trim()
        val iframeSrc = Regex("""src=["']([^"']+)["']""")
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        return iframeSrc ?: cleaned.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("//") }
    }

    private suspend fun extractLarhuStreams(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = runCatching {
            app.get(embedUrl, referer = mainUrl, timeout = 20).text
        }.getOrNull() ?: return

        val streams = mutableSetOf<String>()
        Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            .findAll(page)
            .forEach { streams.add(it.value.replace("\\/", "/")) }
        Regex("""https?://[^\s"'\\]+\.mp4[^\s"'\\]*""", RegexOption.IGNORE_CASE)
            .findAll(page)
            .forEach { streams.add(it.value.replace("\\/", "/")) }

        streams.forEach { stream ->
            if (stream.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(name, stream, embedUrl).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    private fun decodeZtshcodeToken(raw: String): String? {
        val encoded = Regex("""<div[^>]+id=["']([0-9a-fA-F]+)["']""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null

        val decoded = buildString {
            encoded.chunked(3).forEach { chunk ->
                chunk.toIntOrNull(16)?.let { append(it.toChar()) }
            }
        }
        if (decoded.isBlank()) return null

        return Regex("""["']v["']\s*:\s*["']([a-zA-Z0-9_-]+)["']""")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""(19|20)\d{2}""").find(text.orEmpty())?.value?.toIntOrNull()
    }

    private data class ZetaOption(
        val post: String,
        val nume: String,
        val type: String
    )

    private data class ZetaPlayerResponse(
        @param:JsonProperty("embed_url") val embedUrl: String? = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("msg") val msg: String? = null
    )
}

