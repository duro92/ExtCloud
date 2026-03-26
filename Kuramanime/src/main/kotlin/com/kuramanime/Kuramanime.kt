package com.kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v17.kuramanime.ink"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "quick/ongoing?order_by=updated" to "Sedang Tayang",
        "quick/finished?order_by=updated" to "Selesai Tayang",
        "quick/movie?order_by=updated" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("$mainUrl/${request.data}$separator&page=$page").document
        val home = document.select("div.product__item").mapNotNull { card ->
            card.toSearchResult()
        }
        return newHomePageResponse(
            HomePageList(request.name, home),
            hasNext = document.selectFirst("a.page__link i.fa-angle-right") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/anime?search=$query&order_by=updated").document
        return document.select("div.product__item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeAnimeUrl(url)
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("div.anime__details__title h3")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: throw ErrorLoadingException("Missing title")
        val altTitle = document.selectFirst("div.anime__details__title > span")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        val poster = document.selectFirst("div.anime__details__pic")
            ?.attr("data-setbg")
            ?.let(::fixUrlNull)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val rows = document.select("div.anime__details__widget li")
        fun rowValue(label: String): String? {
            return rows.firstOrNull {
                it.selectFirst("span")?.text()?.equals("$label:", true) == true
            }?.select("div.col-9")
                ?.text()
                ?.replace("\\s+".toRegex(), " ")
                ?.trim()
                ?.ifBlank { null }
        }

        val typeText = rowValue("Tipe")
        val statusText = rowValue("Status")
        val year = rowValue("Tayang")
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            ?: rowValue("Musim")
                ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val plot = document.selectFirst("#synopsisField")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        val tags = buildList {
            addAll(
                rows.firstOrNull {
                    it.selectFirst("span")?.text()?.equals("Genre:", true) == true
                }?.select("div.col-9 a")
                    ?.map { it.text().trim().trimEnd(',') }
                    .orEmpty()
            )
            addAll(
                rows.firstOrNull {
                    it.selectFirst("span")?.text()?.equals("Tema:", true) == true
                }?.select("div.col-9 a")
                    ?.map { it.text().trim().trimEnd(',') }
                    .orEmpty()
            )
        }.filter { it.isNotBlank() }.distinct()

        val score = rowValue("Skor")
            ?.replace(",", ".")
            ?.toDoubleOrNull()
            ?.let { Score.from10(it) }
        val showStatus = when {
            statusText?.contains("Sedang Tayang", true) == true -> ShowStatus.Ongoing
            statusText.isNullOrBlank() -> null
            else -> ShowStatus.Completed
        }
        val tvType = getType(typeText)
        val episodes = extractEpisodes(document)
        val trailer = document.selectFirst("iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"]")
            ?.attr("src")

        return if (tvType == TvType.AnimeMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, episodes.first().data, TvType.AnimeMovie, episodes.first().data) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                addTrailer(trailer)
            }
        } else {
            newAnimeLoadResponse(title, fixedUrl, tvType) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.showStatus = showStatus
                this.engName = altTitle
                addEpisodes(DubStatus.Subbed, episodes)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.substringBefore("?")
        val secureRegex = Regex("${Regex.escape(episodeUrl)}\\?.*page=\\d+.*")
        val secureResponse = app.get(
            episodeUrl,
            interceptor = WebViewResolver(
                secureRegex,
                timeout = 20_000L
            )
        )

        val document = runCatching { secureResponse.document }.getOrElse {
            Jsoup.parse(secureResponse.text, episodeUrl)
        }

        val emitted = linkedSetOf<String>()
        val referer = episodeUrl.ifBlank { "$mainUrl/" }

        suspend fun emitLink(
            url: String,
            name: String = "Kuramanime Auto",
            quality: Int? = null,
            type: ExtractorLinkType? = null,
        ) {
            if (url.isBlank() || !emitted.add(url)) return
            val resolvedType = type ?: if (url.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback.invoke(
                newExtractorLink(
                    source = "Kuramanime",
                    name = name,
                    url = url,
                    type = resolvedType
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    this.referer = referer
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                    )
                }
            )
        }

        fun extractQuality(text: String): Int? {
            return Regex("""\b(2160|1440|1080|720|576|480|360|240)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }

        document.select("#player[data-hls-src], video#player[data-hls-src], video[data-hls-src], [data-hls-src]")
            .forEach { player ->
                val hlsUrl = player.attr("abs:data-hls-src")
                    .ifBlank { fixUrlNull(player.attr("data-hls-src")).orEmpty() }
                emitLink(
                    url = hlsUrl,
                    name = "Kuramanime HLS",
                    type = ExtractorLinkType.M3U8
                )
            }

        document.select("video#player source[src]").forEach { source ->
            val videoUrl = source.attr("abs:src").ifBlank { source.attr("src") }
            val quality = source.attr("size").toIntOrNull()
                ?: extractQuality("${source.id()} ${source.attr("label")} ${source.attr("title")}")
            emitLink(
                url = videoUrl,
                name = "Kuramanime ${quality ?: "Auto"}${if (quality != null) "p" else ""}",
                quality = quality
            )
        }

        document.selectFirst("video#player[src], video[src], #player[src]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?.let { videoUrl ->
                emitLink(videoUrl)
            }

        document.select("[data-src], [data-url]").forEach { element ->
            listOf("data-src", "data-url").forEach { attr ->
                val mediaUrl = element.attr("abs:$attr")
                    .ifBlank { fixUrlNull(element.attr(attr)).orEmpty() }
                if (!mediaUrl.contains(".m3u8", true) && !mediaUrl.contains(".mp4", true)) return@forEach
                val quality = extractQuality("${element.text()} ${element.className()} ${element.id()} $mediaUrl")
                emitLink(
                    url = mediaUrl,
                    name = "Kuramanime ${quality ?: "Auto"}${if (quality != null) "p" else ""}",
                    quality = quality
                )
            }
        }

        document.select("a[href]").forEach { anchor ->
            val mediaUrl = anchor.attr("abs:href")
                .ifBlank { fixUrlNull(anchor.attr("href")).orEmpty() }
            if (
                !mediaUrl.contains(".m3u8", true) &&
                !mediaUrl.contains(".mp4", true) &&
                !mediaUrl.contains("amiya.my.id", true)
            ) {
                return@forEach
            }
            val quality = extractQuality("${anchor.text()} ${anchor.className()} $mediaUrl")
            emitLink(
                url = mediaUrl,
                name = "Kuramanime ${quality ?: "Direct"}${if (quality != null) "p" else ""}",
                quality = quality
            )
        }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("h5 a")?.attr("href")
            ?.let(::fixUrl)
            ?.let(::normalizeAnimeUrl)
            ?: return null
        val title = selectFirst("h5 a")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = selectFirst("div.product__item__pic")?.attr("data-setbg")?.let(::fixUrlNull)
        val typeLabel = select("div.product__item__text ul li").firstOrNull()?.text()?.trim()
        val badge = selectFirst("div.product__item__pic div.ep")?.text()?.replace("\\s+".toRegex(), " ")?.trim()
        val tvType = getType(typeLabel)

        return newAnimeSearchResponse(title, href, tvType) {
            posterUrl = poster
            if (badge?.contains("Ep", true) == true) {
                addSub(Regex("(\\d+)").find(badge)?.groupValues?.getOrNull(1)?.toIntOrNull())
            }
        }
    }

    private fun extractEpisodes(document: Document): List<Episode> {
        val episodeLinks = linkedMapOf<String, Pair<String, Int?>>()
        val popoverHtml = document.selectFirst("#episodeLists")?.attr("data-content").orEmpty()
        if (popoverHtml.isNotBlank()) {
            val popoverDoc = Jsoup.parseBodyFragment(popoverHtml)
            popoverDoc.select("a[href*=/episode/]").forEach { anchor ->
                val href = fixUrl(anchor.attr("href"))
                val episode = Regex("Ep\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(anchor.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (!episodeLinks.containsKey(href)) {
                    episodeLinks[href] = anchor.text().trim() to episode
                }
            }
        }

        if (episodeLinks.isEmpty()) {
            document.select("a.ep-button[href*=/episode/]").forEach { anchor ->
                val href = fixUrl(anchor.attr("href"))
                val episode = Regex("/episode/(\\d+)").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (!episodeLinks.containsKey(href)) {
                    episodeLinks[href] = anchor.text().trim() to episode
                }
            }
        }

        return episodeLinks.map { (href, info) ->
            val episodeNumber = info.second
            newEpisode(href) {
                name = info.first.ifBlank { "Episode ${episodeNumber ?: "?"}" }
                episode = episodeNumber
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun normalizeAnimeUrl(url: String): String {
        val fixed = fixUrl(url)
        return fixed.substringBefore("/episode/").substringBefore("/batch/")
    }

    private fun getType(typeLabel: String?): TvType {
        return when {
            typeLabel.isNullOrBlank() -> TvType.Anime
            typeLabel.contains("movie", true) -> TvType.AnimeMovie
            typeLabel.contains("ova", true) || typeLabel.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }
}
