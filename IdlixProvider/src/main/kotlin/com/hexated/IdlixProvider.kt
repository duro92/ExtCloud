package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.nodes.Element
import java.net.URI


class IdlixProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://tv12.idlixku.com"
    private var directUrl = mainUrl
    override var name = "Idlix🎄"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )


    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/trending/page/?get=movies" to "Trending Movies",
        "$mainUrl/trending/page/?get=tv" to "Trending TV Series",
        "$mainUrl/movie/page/" to "Movie Terbaru",
        "$mainUrl/tvseries/page/" to "TV Series Terbaru",
        "$mainUrl/network/amazon/page/" to "Amazon Prime",
        "$mainUrl/network/apple-tv/page/" to "Apple TV+ Series",
        "$mainUrl/network/disney/page/" to "Disney+ Series",
        "$mainUrl/network/HBO/page/" to "HBO Series",
        "$mainUrl/network/netflix/page/" to "Netflix Series",
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
        val url = request.data.split("?")
        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data)
        } else {
            app.get("${url.first()}$page/?${url.lastOrNull()}")
        }
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = (if (nonPaged) {
            document.select("div.items.featured article")
        } else {
            document.select("div.items.full article, div#archive-content article")
        }).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("h3 > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(this.selectFirst("h3 > a")!!.attr("href"))
        val posterUrl = this.select("div.poster > img").attr("src")
        val quality = getQualityFromString(this.select("span.quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val req = app.get("$mainUrl/search/$query/page/$page")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val results = document.select("div.result-item").mapNotNull {
            val titleElement = it.selectFirst("div.title > a") ?: return@mapNotNull null
            val titleWithYear = titleElement.text().trim()

            if (!titleWithYear.contains(Regex("\\(\\d{4}\\)"))) return@mapNotNull null

            val title = titleWithYear.replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(titleElement.attr("href"))
            var posterUrl = it.selectFirst("img")?.attr("src")

            if (posterUrl?.contains("image.tmdb.org/t/p") == true) {
                posterUrl = posterUrl.replace(Regex("/w\\d+/"), "/w200/")
            }

            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title =
            document.selectFirst("div.data > h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")
                ?.trim().toString()
        val images = document.select("div.g-item")

        val poster = images
            .shuffled()
            .firstOrNull()
            ?.selectFirst("a")
            ?.attr("href")
            ?: document.select("div.poster > img").attr("src")
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1)").text().contains("Episodes")
        ) TvType.TvSeries else TvType.Movie
        val description = if (tvType == TvType.Movie) document.select("div.wp-content > p").text().trim() else document.select("div.content > center > p:nth-child(3)").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val ratingValue = document.selectFirst("span.dt_rating_vgs")?.text()?.toDoubleOrNull()
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(it.select("meta[itemprop=name]").attr("content"), it.select("img").attr("src"))
        }

        val recommendations = document.select("div.owl-item").map {
            val recName =
                it.selectFirst("a")!!.attr("href").removeSuffix("/").split("/").last()
            val recHref = it.selectFirst("a")!!.attr("href")
            val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").map {
                val href = it.select("a").attr("href")
                val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                val image = it.select("div.imagen > img").attr("src")
                val episode = it.select("div.numerando").text().replace(" ", "").split("-").last()
                    .toIntOrNull()
                val season = it.select("div.numerando").text().replace(" ", "").split("-").first()
                    .toIntOrNull()
                newEpisode(href)
                {
                        this.name=name
                        this.season=season
                        this.episode=episode
                        this.posterUrl=image
                }
            }
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

        val document = app.get(data).document
        document.select("ul#playeroptionsul > li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>() ?: return@amap
            val metrix = AppUtils.parseJson<AesData>(json.embed_url).m
            val password = generateKey(json.key, metrix)
            val decrypted =
                AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)
                    ?.fixBloat() ?: return@amap

          when {
                !decrypted.contains("youtube") ->
                    loadExtractor(decrypted,directUrl,subtitleCallback,callback)
                else -> return@amap
            }
        }

        return true
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
        @JsonProperty("key") val key: String,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )

}
