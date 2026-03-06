package com.midasxxi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder


class Midasxxi : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://ssstik.tv"
    private var directUrl = mainUrl
    override var name = "MidasXXI🙈"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)


    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movie Terbaru",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/drama/" to "Drama",
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val base = request.data.trimEnd('/')
        val target = if (page <= 1) "$base/" else "$base/page/$page/"
        val req = app.get(target)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = document.select(
            "div#archive-content article.item, article.item.movies, article.item.tvshows, div.items.full article"
        ).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episodes/") -> {
                val slug = uri.substringAfter("/episodes/").substringBefore("/")
                val base = Regex("(.+)-\\d+x\\d+$").find(slug)?.groupValues?.getOrNull(1)
                    ?: slug.substringBeforeLast("-")
                "$mainUrl/tvshows/${base.trimEnd('-')}"
            }
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvshows/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = this.selectFirst("div.data > h3 > a, h3 > a") ?: return null
        val title = titleAnchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(titleAnchor.attr("href"))
        val posterUrl = this.selectFirst("div.poster img, img")?.getImageAttr()
        val tvType = if (
            this.classNames().any { it.equals("tvshows", true) } ||
            href.contains("/tvshows/", true)
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
        if (tvType != TvType.Movie) return null
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/search/$encoded/page/$page/",
            "$mainUrl/page/$page/?s=$encoded",
            "$mainUrl/?s=$encoded",
        )

        val req = urls.firstNotNullOfOrNull { url ->
            runCatching { app.get(url) }.getOrNull()?.takeIf {
                it.document.select("div.result-item, div#archive-content article.item, article.item.movies, article.item.tvshows").isNotEmpty()
            }
        } ?: return null

        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val listFromSearchPage = document.select("div.result-item").mapNotNull {
            val titleElement = it.selectFirst("div.title > a, h3 > a, a") ?: return@mapNotNull null
            val title = titleElement.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            if (title.isBlank()) return@mapNotNull null

            val href = getProperLink(titleElement.attr("href"))
            var posterUrl = it.selectFirst("img")?.getImageAttr()
            if (posterUrl?.contains("image.tmdb.org/t/p") == true) {
                posterUrl = posterUrl.replace(Regex("/w\\d+/"), "/w200/")
            }
            val type = if (
                href.contains("/tvshows/", true) ||
                href.contains("/tvseries/", true) ||
                it.classNames().any { c -> c.equals("tvshows", true) }
            ) TvType.TvSeries else TvType.Movie
            if (type != TvType.Movie) return@mapNotNull null

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }

        val listFromArchive = document
            .select("div#archive-content article.item, article.item.movies, article.item.tvshows")
            .mapNotNull { it.toSearchResult() }

        return (listFromSearchPage + listFromArchive)
            .distinctBy { it.url }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title = document.selectFirst("div.data > h1, h1, h1.epih1")?.text()
            ?.replace(Regex("\\(\\d{4}\\)"), "")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
                .orEmpty()

        val poster = document.selectFirst("div.poster > img, .poster img")?.getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.g-item a[href]")?.attr("href")
            ?: ""

        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex("""(19|20)\d{2}""")
            .find(
                document.selectFirst("div.extra span.date, span.date, .custom_fields:contains(Release date) .valor")
                    ?.text()
                    .orEmpty()
            )
            ?.value
            ?.toIntOrNull()

        val tvType = when {
            request.url.contains("/episodes/", true) -> TvType.TvSeries
            request.url.contains("/tvshows/", true) -> TvType.TvSeries
            document.selectFirst("#single[itemtype*=TVSeries]") != null -> TvType.TvSeries
            else -> TvType.Movie
        }

        val description = document.selectFirst("#info .wp-content > p, div[itemprop=description].wp-content > p, .wp-content > p")
            ?.text()
            ?.trim()
            .orEmpty()

        val trailer = document.selectFirst("#trailer iframe[src], .trailer iframe[src], iframe[src*='youtube'], iframe[src*='youtu.be']")
            ?.attr("src")

        val ratingValue = document.selectFirst("div.starstruck-main[data-rating], .starstruck-main[data-rating], .dt_rating_data[data-rating]")
            ?.attr("data-rating")
            ?.toDoubleOrNull()
            ?: document.selectFirst("span.dt_rating_vgs")?.text()?.toDoubleOrNull()

        val actors = document.select("#cast .person, div.persons > div[itemprop=actor]").mapNotNull {
            val actorName = it.selectFirst("meta[itemprop=name]")?.attr("content")
                ?.takeIf { n -> n.isNotBlank() }
                ?: it.selectFirst("div.data > div.name > a, .name a, a[href*='/cast/']")?.text()
                ?.trim()
                ?: return@mapNotNull null
            val actorImage = it.selectFirst("div.img > img, img")?.getImageAttr()
            Actor(actorName, actorImage)
        }

        val recommendations = document
            .select("div.srelacionados article.item, div#single_relacionados article, div#single_relacionados article.item, div.owl-item article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("#episodes ul.episodios > li, #seasons ul.episodios > li, #serie_contenido ul.episodios > li, ul.episodios > li")
                .mapNotNull { li ->
                    val href = li.selectFirst("div.episodiotitle > a, a[href*='/episodes/']")
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val name = fixTitle(
                        li.selectFirst("div.episodiotitle > a, .episodiotitle a")
                            ?.text()
                            ?.trim()
                            .orEmpty()
                    )
                    val image = li.selectFirst("div.imagen > img, .imagen img, img")?.getImageAttr()
                    val numText = li.selectFirst("div.numerando")?.text().orEmpty()
                    val (season, episode) = parseEpisodeNumbers(numText, href)

                    newEpisode(href) {
                        this.name = name.ifBlank { "Episode ${episode ?: "?"}" }
                        this.season = season
                        this.episode = episode
                        this.posterUrl = image
                    }
                }
                .distinctBy { it.data }
                .sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
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

        val pageResp = app.get(data)
        val pageUrl = pageResp.url
        directUrl = getBaseUrl(pageUrl)
        val document = pageResp.document
        val emitted = mutableSetOf<String>()

        suspend fun emitExtractor(rawUrl: String?) {
            val cleaned = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (cleaned.startsWith("javascript:", true)) return
            val fixed = fixUrl(cleaned)
            if (!emitted.add(fixed)) return

            if (fixed.contains("youtube", true) || fixed.contains("youtu.be", true)) return

            loadExtractor(fixed, pageUrl, subtitleCallback, callback)
        }

        // Initial iframe already rendered on some pages.
        document.select("#dooplay_player_response iframe[src], #playcontainer iframe[src], iframe.metaframe[src], iframe[src*='playcinematic'], source[src]")
            .forEach { iframe ->
                emitExtractor(iframe.attr("src"))
            }

        val defaultType = if (pageUrl.contains("/episodes/", true) || pageUrl.contains("/tvshows/", true)) {
            "tv"
        } else {
            "movie"
        }

        document.select("ul#playeroptionsul > li.dooplay_player_option[data-post], #playeroptionsul li[data-post]").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type").ifBlank { defaultType }
                )
            }.amap { (id, nume, type) ->
            if (id.isBlank() || nume.isBlank() || type.isBlank()) return@amap
            if (nume.equals("trailer", true)) return@amap

            val ajaxResp = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                referer = pageUrl,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            )

            val json = ajaxResp.parsedSafe<ResponseHash>() ?: runCatching {
                AppUtils.parseJson<ResponseHash>(ajaxResp.text.replace("\\/", "/"))
            }.getOrNull() ?: return@amap

            // New site response is plain URL: {"embed_url":"https://playcinematic.com/video/xxx","type":"iframe"}
            val rawEmbed = json.embed_url.trim()
            if (rawEmbed.startsWith("http", true) || rawEmbed.startsWith("//")) {
                emitExtractor(rawEmbed)
                return@amap
            }

            // Fallback for old encrypted flow (kept for compatibility with mirrors).
            if (json.key.isNullOrBlank()) return@amap
            val metrix = runCatching { AppUtils.parseJson<AesData>(rawEmbed).m }.getOrNull() ?: return@amap
            val password = generateKey(json.key, metrix)
            val decrypted = AesHelper.cryptoAESHandler(rawEmbed, password.toByteArray(), false)
                ?.fixBloat()
                ?: return@amap

            emitExtractor(decrypted)
        }

        return emitted.isNotEmpty()
    }

    private fun parseEpisodeNumbers(numberText: String, episodeUrl: String): Pair<Int?, Int?> {
        Regex("""(\d+)\s*-\s*(\d+)""").find(numberText)?.destructured?.let { (s, e) ->
            return s.toIntOrNull() to e.toIntOrNull()
        }

        val slug = episodeUrl.substringAfter("/episodes/").substringBefore("/")
        Regex("""-(\d+)x(\d+)$""").find(slug)?.destructured?.let { (s, e) ->
            return s.toIntOrNull() to e.toIntOrNull()
        }

        return null to null
    }

    private fun generateKey(r: String, m: String): String {
        val rList = r.split("\\x").toTypedArray()
        var n = ""
        val decodedM = safeBase64Decode(m.reversed())
        for (s in decodedM.split("|")) {
            n += "\\x" + rList[Integer.parseInt(s) + 1]
        }
        return n
    }

    private fun safeBase64Decode(input: String): String {
        var paddedInput = input
        val remainder = input.length % 4
        if (remainder != 0) {
            paddedInput += "=".repeat(4 - remainder)
        }
        return base64Decode(paddedInput)
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }


    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )

}
