package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s11.nontonanimeid.boats"
    override var name = "NontonAnimeID🦋"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        var context: android.content.Context? = null

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=&type=" to "Anime Terbaru",
        "$mainUrl/anime/?mode=&sort=series_popularity&status=&type=" to "Anime Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val pageUrl = if (page == 1) {
            request.data
        } else {
            val query = request.data.substringAfter("?", "")
            val queryPart = if (query.isNotBlank()) "?$query" else ""
            "$mainUrl/anime/page/$page/$queryPart"
        }

        val document = app.get(pageUrl).document
        val home = document.select("a.as-anime-card").mapNotNull {
            it.toSearchResult()
        }
        val hasNextByLink = document.select(
            "a.next.page-numbers, a.nextpostslink, a[aria-label*=Berikutnya], a[aria-label*=next]"
        ).isNotEmpty()
        val totalPages = Regex("dari\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(document.selectFirst("nav.pagination .pages, nav.pagination span.pages")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val hasNext = hasNextByLink || (totalPages != null && page < totalPages)
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val card = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = card.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return null
        val title = card.selectFirst(".as-anime-title")?.text()?.trim()
            ?: card.attr("title").trim()
            ?: return null
        val posterUrl =
            fixUrlNull(card.selectFirst(".as-card-thumbnail img, img")?.getImageAttr())

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")!!.text().trim()
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(
                it.selectFirst(".boxinfores > span.typeseries")!!.text()
            )
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val canonicalUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val req = app.get(canonicalUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs, h1.entry-title")?.text()
            ?.replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+Sub\\s+Indo$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: return null
        val poster = document.selectFirst(".anime-card__sidebar img, .poster > img")?.getImageAttr()
        val tags = document.select(".anime-card__genres .genre-tag, .tagline > a").map { it.text() }

        val year = Regex("(19|20)\\d{2}").find(
            document.select(".details-list li:contains(Aired), .info-item.season, .bottomtitle").text()
        )?.value?.toIntOrNull()
        val statusText = document.selectFirst(".info-item.status-finish, .info-item.status-airing, span.statusseries")
            ?.text()
            ?.trim()
            .orEmpty()
        val status = getStatus(
            when {
                statusText.contains("airing", true) -> "Currently Airing"
                statusText.contains("currently", true) -> "Currently Airing"
                statusText.contains("finish", true) -> "Finished Airing"
                else -> statusText
            }
        )
        val type = getType(
            document.select("span.typeseries, .anime-card__score, .details-list, .bottomtitle")
                .text()
                .trim()
        )
        val rating = Regex("(\\d+(?:\\.\\d+)?)").find(
            document.selectFirst(".anime-card__score")?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: document.select("span.nilaiseries").text().trim().toDoubleOrNull()
        val description = document.select(".synopsis-prose > p, .entry-content.seriesdesc > p")
            .text()
            .trim()
        val trailer = document.selectFirst("a.trailerbutton")?.attr("href")

        val episodes = if (document.select(".episode-list-items a.episode-item").isNotEmpty()) {
            val episodeMap = linkedMapOf<String, EpisodeEntry>()
            document.select(".episode-list-items a.episode-item")
                .mapNotNull { parseEpisodeAnchor(it) }
                .forEach { episodeMap[it.link] = it }

            val loadMoreEpisodes = loadMoreEpisodeAnchors(
                document = document,
                refererUrl = canonicalUrl,
                existingLinks = episodeMap.keys
            )
            loadMoreEpisodes.forEach { episodeMap[it.link] = it }

            episodeMap.values
                .map { item ->
                    newEpisode(item.link) { this.episode = item.episode }
                }
                .sortedBy { it.episode ?: Int.MAX_VALUE }
        } else if (document.select("button.buttfilter").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp =
                document.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "")
                    .toString()
            Jsoup.parse(
                app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "misha_number_of_results" to numEp,
                        "misha_order_by" to "date-DESC",
                        "action" to "mishafilter",
                        "series_id" to id
                    ),
                    referer = canonicalUrl
                ).parsed<EpResponse>().content
            ).select("li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1) ?: it.selectFirst("a")?.text()
                val link = this.fixUrl(it.selectFirst("a")!!.attr("href"))
                newEpisode(link) { this.episode = episode?.toIntOrNull() }
            }.reversed()
        } else {
            document.select("ul.misha_posts_wrap2 > li").map {
                val episode = Regex("Episode\\s?(\\d+)").find(
                    it.selectFirst("a")?.text().toString()
                )?.groupValues?.getOrNull(1) ?: it.selectFirst("a")?.text()
                val link = it.select("a").attr("href")
                newEpisode(link) { this.episode = episode?.toIntOrNull() }
            }.reversed()
        }

        val recommendations = document.select(".related a.as-anime-card, .result > li").mapNotNull {
            val card = if (it.tagName() == "a") it else it.selectFirst("a")
            val epHref = card?.attr("href")?.takeIf { href -> href.isNotBlank() } ?: return@mapNotNull null
            val epTitle = card.selectFirst(".as-anime-title, h3")?.text()?.trim()
                ?: card.attr("title").trim()
            if (epTitle.isBlank()) return@mapNotNull null
            val epPoster = card.selectFirst(".as-card-thumbnail img, .top > img, img")?.getImageAttr()
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            this.score = Score.from10(rating)
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    private suspend fun loadMoreEpisodeAnchors(
        document: Document,
        refererUrl: String,
        existingLinks: Collection<String>
    ): List<EpisodeEntry> {
        val scriptBody = document.select("script[src^=data:text/javascript;base64,]")
            .mapNotNull { it.attr("src").substringAfter("base64,", "").takeIf { raw -> raw.isNotBlank() } }
            .mapNotNull { encoded -> runCatching { base64Decode(encoded) }.getOrNull() }
            .firstOrNull { decoded -> decoded.contains("misha_loadmore_params2") }
            ?: return emptyList()

        val start = scriptBody.indexOf('{')
        val end = scriptBody.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()

        val params = runCatching { JSONObject(scriptBody.substring(start, end + 1)) }.getOrNull()
            ?: return emptyList()

        val ajaxUrl = params.optString("ajaxurl").takeIf { it.isNotBlank() } ?: return emptyList()
        val nonce = params.optString("nonce").takeIf { it.isNotBlank() } ?: return emptyList()
        val query = params.optString("posts").takeIf { it.isNotBlank() } ?: return emptyList()
        val type = params.optString("type")
        val postsToDisplay = params.optString("posts_to_display")
        val isLargeSeries = params.optString("is_large_series")
        val totalPosts = params.optString("total_posts").toIntOrNull() ?: return emptyList()
        val maxPage = params.optString("max_page").toIntOrNull() ?: return emptyList()
        var currentPage = params.optString("current_page").toIntOrNull() ?: 1

        val seenLinks = existingLinks.toMutableSet()
        val foundEpisodes = mutableListOf<EpisodeEntry>()

        while (seenLinks.size < totalPosts && currentPage <= maxPage) {
            val responseHtml = runCatching {
                app.post(
                    url = this.fixUrl(ajaxUrl),
                    data = mapOf(
                        "action" to "loadmore2",
                        "nonce" to nonce,
                        "query" to query,
                        "page" to currentPage.toString(),
                        "type" to type,
                        "posts_to_display" to postsToDisplay,
                        "is_large_series" to isLargeSeries,
                        "total_posts" to totalPosts.toString(),
                    ),
                    referer = refererUrl,
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                    )
                ).text
            }.getOrNull() ?: break

            val anchors = Jsoup.parse(responseHtml).select("a.episode-item")
            if (anchors.isEmpty()) break

            var newCount = 0
            anchors.mapNotNull { parseEpisodeAnchor(it) }.forEach { episode ->
                if (seenLinks.add(episode.link)) {
                    foundEpisodes.add(episode)
                    newCount++
                }
            }

            if (newCount == 0) break
            currentPage++
        }

        return foundEpisodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val iframeLinks = linkedSetOf<String>()

        fun normalizeUrl(value: String?): String? {
            val raw = value
                ?.trim()
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return when {
                raw.startsWith("about:") -> null
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> fixUrl(raw)
            }
        }

        fun collectIframes(doc: Document) {
            doc.select("iframe").forEach { iframe ->
                normalizeUrl(iframe.attr("src"))?.let { iframeLinks.add(it) }
                normalizeUrl(iframe.attr("data-src"))?.let { iframeLinks.add(it) }
            }
        }

        collectIframes(document)

        val ajaxConfigScript = document.selectFirst("script#ajax_video-js-extra")
            ?.attr("src")
            ?.substringAfter("base64,", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded -> base64Decode(encoded) }
            .orEmpty()
        val ajaxUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it) }
            ?: "$mainUrl/wp-admin/admin-ajax.php"
        val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)

        if (!nonce.isNullOrBlank()) {
            document.select(
                ".serverplayer[data-post][data-type][data-nume], " +
                    ".container1 > ul > li[data-post][data-type][data-nume], " +
                    "[data-post][data-type][data-nume]"
            ).forEach { serverItem ->
                    val dataPost = serverItem.attr("data-post")
                    val dataNume = serverItem.attr("data-nume")
                    val serverName = serverItem.attr("data-type").trim()
                    if (dataPost.isBlank() || dataNume.isBlank() || serverName.isBlank()) return@forEach

                    val response = app.post(
                        url = ajaxUrl,
                        data = mapOf(
                            "action" to "player_ajax",
                            "nonce" to nonce,
                            "serverName" to serverName,
                            "nume" to dataNume,
                            "post" to dataPost,
                        ),
                        referer = data,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                        )
                    ).text
                    collectIframes(Jsoup.parse(response))
                }
        }

        iframeLinks.toList().amap { link ->
            val nestedLink = if (link.contains("/video-frame/")) {
                app.get(link, referer = data).document.selectFirst("iframe")
                    ?.attr("data-src")
                    ?.let { nested -> normalizeUrl(nested) }
            } else {
                null
            }
            loadExtractor(nestedLink ?: link, data, subtitleCallback, callback)
        }

        return iframeLinks.isNotEmpty()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun parseEpisodeAnchor(anchor: Element): EpisodeEntry? {
        val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(anchor.text().trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val linkRaw = anchor.attr("href").ifBlank { anchor.attr("data-episode-url") }
        val link = linkRaw
            .takeIf { value -> value.isNotBlank() }
            ?.let { value -> this.fixUrl(value) }
            ?: return null
        val safeEpisode = episode
            ?: extractEpisodeFromLink(link)
            ?: extractEpisodeFromLink(linkRaw)
        return EpisodeEntry(link = link, episode = safeEpisode)
    }

    private fun extractEpisodeFromLink(link: String?): Int? {
        if (link.isNullOrBlank()) return null
        return Regex("(?:episode|ep)[^\\d]*(\\d+)", RegexOption.IGNORE_CASE)
            .find(link)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private data class EpisodeEntry(
        val link: String,
        val episode: Int?,
    )

    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )

}
