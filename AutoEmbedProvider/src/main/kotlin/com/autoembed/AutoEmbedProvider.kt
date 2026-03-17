package com.autoembed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AutoEmbedProvider : MainAPI() {
    override var mainUrl = "https://watch-v2.autoembed.cc"
    override var name = "AutoEmbed😒"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val vidSrcApi = "https://vidsrc.net"
    private val vixsrcApi = "https://vixsrc.to"
    private val vixsrcProxy = "https://proxy.heistotron.uk"

    override val mainPage = mainPageOf(
        "$mainUrl/trending/movie" to "Trending Movies",
        "$mainUrl/movie/popular" to "Popular Movies",
        "$mainUrl/movie/now-playing" to "Now Playing Movies",
        "$mainUrl/movie/top-rated" to "Top Rated Movies",
        "$mainUrl/movie/upcoming" to "Upcoming Movies",
        "$mainUrl/trending/tv" to "Trending TV",
        "$mainUrl/tv/popular" to "Popular TV",
        "$mainUrl/tv/airing-today" to "Airing Today TV",
        "$mainUrl/tv/top-rated" to "Top Rated TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPagedUrl(request.data, page)).document
        val items = document.parseGridItems()
        val hasNext = document.selectFirst("""a[aria-label="Go to next page"]""") != null
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.urlEncoded()}&page=1"
        return app.get(url).document.parseGridItems()
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseSiteMediaUrl(url)
        val detailUrl = when (media.type) {
            "movie" -> "$tmdbApi/movie/${media.id}?api_key=$tmdbApiKey&append_to_response=external_ids,credits,recommendations,videos"
            else -> "$tmdbApi/tv/${media.id}?api_key=$tmdbApiKey&append_to_response=external_ids,credits,recommendations,videos"
        }
        val detail = app.get(detailUrl).parsedSafe<TmdbDetail>()
            ?: throw ErrorLoadingException("Invalid TMDB response")

        val title = detail.title ?: detail.name ?: throw ErrorLoadingException("Missing title")
        val poster = detail.posterPath.toPosterUrl()
        val background = detail.backdropPath.toBackdropUrl()
        val year = (detail.releaseDate ?: detail.firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val tags = detail.genres.orEmpty().mapNotNull { it.name }.takeIf { it.isNotEmpty() }
        val actors = detail.credits?.cast.orEmpty().mapNotNull { cast ->
            val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
            ActorData(
                actor = Actor(actorName, cast.profilePath.toPosterUrl()),
                roleString = cast.character
            )
        }.takeIf { it.isNotEmpty() }
        val recommendations = detail.recommendations?.results.orEmpty().mapNotNull { it.toRecommendation() }
            .takeIf { it.isNotEmpty() }
        val trailers = detail.videos?.results.orEmpty()
            .mapNotNull { video ->
                if (video.site.equals("YouTube", true) && !video.key.isNullOrBlank()) {
                    "https://www.youtube.com/watch?v=${video.key}"
                } else {
                    null
                }
            }

        return if (media.type == "movie") {
            newMovieLoadResponse(
                title,
                "$mainUrl/movie/${media.id}",
                TvType.Movie,
                LinkData(
                    tmdbId = media.id,
                    imdbId = detail.externalIds?.imdbId,
                    type = media.type
                ).toJson()
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                plot = detail.overview
                this.year = year
                duration = detail.runtime
                tags?.let { this.tags = it }
                detail.voteAverage?.let { score = Score.from10(it) }
                actors?.let { this.actors = it }
                recommendations?.let { this.recommendations = it }
                if (trailers.isNotEmpty()) addTrailer(trailers)
            }
        } else {
            val episodes = buildEpisodes(media.id, detail.externalIds?.imdbId)
            newTvSeriesLoadResponse(
                title,
                "$mainUrl/tv/${media.id}",
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = background
                plot = detail.overview
                this.year = year
                tags?.let { this.tags = it }
                detail.voteAverage?.let { score = Score.from10(it) }
                actors?.let { this.actors = it }
                recommendations?.let { this.recommendations = it }
                showStatus = detail.status.toShowStatus()
                if (trailers.isNotEmpty()) addTrailer(trailers)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        invokeVidsrc(
            imdbId = linkData.imdbId,
            season = linkData.season,
            episode = linkData.episode,
            callback = callback
        )
        invokeVixsrc(
            tmdbId = linkData.tmdbId,
            season = linkData.season,
            episode = linkData.episode,
            callback = callback
        )
        return true
    }

    private suspend fun buildEpisodes(tmdbId: Int, imdbId: String?): List<Episode> {
        val detail = app.get("$tmdbApi/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetail>()
            ?: return emptyList()
        val allEpisodes = mutableListOf<Episode>()
        detail.seasons.orEmpty()
            .filter { it.seasonNumber != null }
            .forEach { season ->
                val seasonNumber = season.seasonNumber ?: return@forEach
                val seasonData = app.get(
                    "$tmdbApi/tv/$tmdbId/season/$seasonNumber?api_key=$tmdbApiKey"
                ).parsedSafe<TmdbSeasonDetail>() ?: return@forEach

                seasonData.episodes.orEmpty().forEach { episode ->
                    val episodeNumber = episode.episodeNumber ?: return@forEach
                    allEpisodes += newEpisode(
                        LinkData(
                            tmdbId = tmdbId,
                            imdbId = imdbId,
                            type = "tv",
                            season = seasonNumber,
                            episode = episodeNumber
                        ).toJson()
                    ) {
                        this.name = episode.name ?: "Episode $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = episode.stillPath.toPosterUrl()
                        this.description = episode.overview
                        episode.voteAverage?.let { score = Score.from10(it) }
                    }.apply {
                        addDate(episode.airDate)
                    }
                }
            }
        return allEpisodes
    }

    private suspend fun invokeVidsrc(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (imdbId.isNullOrBlank()) return
        val cloudnestraApi = "https://cloudnestra.com"
        val url = if (season == null) {
            "$vidSrcApi/embed/movie?imdb=$imdbId"
        } else {
            "$vidSrcApi/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        }

        app.get(url).document.select(".serversList .server").forEach { server ->
            if (!server.text().equals("CloudStream Pro", ignoreCase = true)) return@forEach

            val hash = app.get("$cloudnestraApi/rcp/${server.attr("data-hash")}").text
                .substringAfter("/prorcp/")
                .substringBefore("'")
            if (hash.isBlank()) return@forEach

            val result = app.get("$cloudnestraApi/prorcp/$hash").text
            val streamUrl = Regex("""https:[^"'\\]+\.m3u8[^"'\\]*""").find(result)?.value
                ?: return@forEach

            callback.invoke(
                newExtractorLink(
                    source = "Vidsrc",
                    name = "Vidsrc",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }

    private suspend fun invokeVixsrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (tmdbId == null) return

        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vixsrcApi/$type/$tmdbId"
        } else {
            "$vixsrcApi/$type/$tmdbId/$season/$episode"
        }

        val script = app.get(url).document
            .selectFirst("script:containsData(window.masterPlaylist)")?.data()
            ?: return

        val match = Regex("""'token':\s*'(\w+)'[\S\s]+'expires':\s*'(\w+)'[\S\s]+url:\s*'(\S+)'""")
            .find(script) ?: return
        val (token, expires, path) = match.destructured

        val primaryUrl = "$path?token=$token&expires=$expires&h=1&lang=en"
        val proxiedUrl =
            "$vixsrcProxy/p/${
                base64EncodeString(
                    "$vixsrcProxy/api/proxy/m3u8?url=${encodeForProxy(primaryUrl)}&source=sakura|ananananananananaBatman!"
                )
            }"

        listOf(
            VixsrcSource("Vixsrc [Alpha]", primaryUrl, url),
            VixsrcSource("Vixsrc [Beta]", proxiedUrl, "$vixsrcApi/"),
        ).forEach { source ->
            callback.invoke(
                newExtractorLink(
                    source = source.name,
                    name = source.name,
                    url = source.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = source.referer
                    headers = mapOf("Accept" to "*/*")
                }
            )
        }
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        val joiner = if (url.contains("?")) "&" else "?"
        return "$url${joiner}page=$page"
    }

    private fun parseSiteMediaUrl(url: String): SiteMedia {
        val clean = url.substringBefore("?")
        val parts = clean.trimEnd('/').split("/")
        val type = parts.getOrNull(parts.lastIndex - 1) ?: throw ErrorLoadingException("Invalid type")
        val id = parts.lastOrNull()?.toIntOrNull() ?: throw ErrorLoadingException("Invalid ID")
        return SiteMedia(type = type, id = id)
    }

    private fun Document.parseGridItems(): List<SearchResponse> {
        return select("div.grid-list a[href^=/movie/], div.grid-list a[href^=/tv/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").takeIf { it.startsWith("/movie/") || it.startsWith("/tv/") } ?: return null
        val title = selectFirst("h2")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val year = selectFirst("p")?.text()?.trim()?.toIntOrNull()
        val scoreValue = select("div")
            .mapNotNull { it.text().trim().toDoubleOrNull() }
            .firstOrNull()
        val absoluteUrl = href.toAbsoluteUrl()

        return if (href.startsWith("/movie/")) {
            newMovieSearchResponse(title, absoluteUrl, TvType.Movie) {
                posterUrl = poster
                this.year = year
                scoreValue?.let { score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, absoluteUrl, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                scoreValue?.let { score = Score.from10(it) }
            }
        }
    }

    private fun TmdbMedia.toRecommendation(): SearchResponse? {
        val title = this.title ?: this.name ?: return null
        return if ((this.mediaType ?: "movie") == "movie") {
            newMovieSearchResponse(title, "$mainUrl/movie/${id ?: return null}", TvType.Movie) {
                posterUrl = posterPath.toPosterUrl()
                voteAverage?.let { score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/${id ?: return null}", TvType.TvSeries) {
                posterUrl = posterPath.toPosterUrl()
                voteAverage?.let { score = Score.from10(it) }
            }
        }
    }

    private fun String?.toPosterUrl(): String? {
        if (this.isNullOrBlank()) return null
        return if (startsWith("/")) "https://image.tmdb.org/t/p/w500/$this" else this
    }

    private fun String?.toBackdropUrl(): String? {
        if (this.isNullOrBlank()) return null
        return if (startsWith("/")) "https://image.tmdb.org/t/p/original/$this" else this
    }

    private fun String?.toShowStatus(): ShowStatus? {
        return when (this) {
            "Returning Series", "In Production", "Planned" -> ShowStatus.Ongoing
            "Ended", "Canceled" -> ShowStatus.Completed
            else -> null
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.toAbsoluteUrl(): String {
        return if (startsWith("http")) this else "$mainUrl$this"
    }

    private fun encodeForProxy(input: String): String {
        return URLEncoder.encode(input, "UTF-8").replace("+", "%20")
    }

    private fun base64EncodeString(input: String): String {
        return Base64.getEncoder().encodeToString(input.toByteArray())
    }

    data class SiteMedia(
        val type: String,
        val id: Int,
    )

    data class LinkData(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    data class VixsrcSource(
        val name: String,
        val url: String,
        val referer: String,
    )

    data class TmdbGenres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo>? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    )

    data class TmdbResults(
        @JsonProperty("results") val results: List<TmdbMedia>? = null,
    )

    data class TmdbMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
    )

    data class TmdbSeasonDetail(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: List<TmdbGenres>? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("recommendations") val recommendations: TmdbResults? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
    )
}
