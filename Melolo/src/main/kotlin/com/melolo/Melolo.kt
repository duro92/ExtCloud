package com.melolo

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Melolo : MainAPI() {
    private val siteUrl = "https://melolo.com"
    override var mainUrl = "https://melolo.com/id"
    override var name = "Melolo ID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/ranking" to "Peringkat",
        "/category/romance" to "Romansa",
        "/category/ceo" to "CEO",
        "/category/revenge" to "Pembalasan",
        "/category/rebirth" to "Kelahiran Kembali",
        "/category/urban" to "Perkotaan",
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
    )

    private val dramaPath = "/id/dramas/"
    private val categoryPath = "/id/category/"
    private val episodeStreamRegex = Regex("""\\"episode_id\\":(\d+),\\"url\\":\\"([^"]+)""")
    private val directStreamRegex = Regex("""https://[^"\\]+?\.mp4[^"\\]*""")
    private val episodeNumberRegex = Regex("""/ep(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList()),
                hasNext = false
            )
        }

        val doc = getDocument(request.data)
        val items = parseDramaCards(doc)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val doc = getDocument("/search?keyword=${encodeQuery(keyword)}")
        return parseDramaCards(doc)
            .map { scoreDramaCard(keyword, it) to it }
            .filter { it.first > 0 }
            .sortedWith(
                compareByDescending<Pair<Int, DramaCard>> { it.first }
                    .thenByDescending { it.second.title.equals(keyword, true) }
                    .thenByDescending { it.second.title.contains(keyword, true) }
                    .thenByDescending { it.second.episodeCount ?: 0 }
            )
            .mapNotNull { it.second.toSearchResult() }
            .distinctBy { it.url }
            .take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeUrl(url)
        val doc = getDocument(normalizedUrl)
        val titleElement = doc.selectFirst("h1")
        val title = titleElement?.text()?.trim().orEmpty().ifBlank {
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
                .orEmpty()
        }.ifBlank {
            extractDramaSlug(normalizedUrl)
                ?.replace('-', ' ')
                ?.split(Regex("\\s+"))
                ?.joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .orEmpty()
        }
        if (title.isBlank()) throw ErrorLoadingException("Judul Melolo tidak ditemukan")

        val posterUrl = cleanImageUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: findHeroImage(doc)
        )
        val plot = extractDramaPlot(doc)
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = extractTopTags(doc, titleElement)
        val dramaSlug = extractDramaSlug(normalizedUrl)
            ?: throw ErrorLoadingException("Slug drama Melolo tidak ditemukan")

        val episodes = doc.select("a[href*=$dramaPath$dramaSlug/ep]")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href"))
                val episodeNumber = extractEpisodeNumber(href) ?: return@mapNotNull null
                episodeNumber to href
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .map { (episodeNumber, episodeUrl) ->
                newEpisode(episodeUrl) {
                    name = "Episode $episodeNumber"
                    episode = episodeNumber
                }
            }

        if (episodes.isEmpty()) throw ErrorLoadingException("Episode Melolo tidak ditemukan")

        return newTvSeriesLoadResponse(
            title,
            normalizedUrl,
            TvType.AsianDrama,
            episodes
        ) {
            posterUrl?.let { this.posterUrl = it }
            plot?.let { this.plot = it }
            this.tags = tags
            showStatus = ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = normalizeUrl(data)
        val episodeNumber = extractEpisodeNumber(episodeUrl)
        val response = app.get(episodeUrl, headers = defaultHeaders)
        val html = response.text

        val streams = LinkedHashMap<Int, String>()
        episodeStreamRegex.findAll(html).forEach { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            val streamUrl = decodeScriptValue(match.groupValues[2]).trim()
            if (streamUrl.isNotBlank()) {
                streams.putIfAbsent(number, streamUrl)
            }
        }

        val directUrl = episodeNumber?.let { streams[it] }
            ?: streams.values.firstOrNull()
            ?: directStreamRegex.find(html)?.value?.let(::decodeScriptValue)

        if (directUrl.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                    name = "$name ${episodeNumber?.let { "Episode $it" } ?: "Stream"}",
                    url = directUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    quality = Qualities.Unknown.value
                    referer = episodeUrl
                    headers = mapOf(
                        "Referer" to episodeUrl,
                        "Origin" to siteUrl,
                        "User-Agent" to defaultHeaders.getValue("User-Agent"),
                    )
                }
        )

        return true
    }

    private suspend fun getDocument(pathOrUrl: String): Document {
        val url = if (pathOrUrl.startsWith("http", true)) pathOrUrl else "$mainUrl$pathOrUrl"
        return app.get(url, headers = defaultHeaders).document
    }

    private fun parseDramaCards(document: Document): List<DramaCard> {
        val cards = LinkedHashMap<String, DramaCard>()

        document.select("a[href*=$dramaPath]")
            .asSequence()
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href"))
                if (!href.contains(dramaPath) || href.contains("/ep")) return@mapNotNull null

                val title = anchor.text().trim()
                    .ifBlank { anchor.attr("aria-label").trim() }
                if (title.isBlank()) return@mapNotNull null

                val container = findCardContainer(anchor, href) ?: return@mapNotNull null
                val posterUrl = cleanImageUrl(container.selectFirst("img[src]")?.attr("src"))
                    ?: return@mapNotNull null
                val tags = container.select("a[href*=$categoryPath]")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                val plot = container.select("div, p")
                    .asSequence()
                    .map { it.text().trim() }
                    .filter { isPotentialPlot(it, title, tags) }
                    .maxByOrNull { it.length }
                val episodeCount = extractEpisodeCount(container.text())

                DramaCard(
                    title = title,
                    url = href,
                    posterUrl = posterUrl,
                    plot = plot,
                    tags = tags,
                    episodeCount = episodeCount
                )
            }
            .forEach { card ->
                cards.putIfAbsent(card.url, card)
            }

        return cards.values.toList()
    }

    private fun findCardContainer(anchor: Element, href: String): Element? {
        return anchor.parents().firstOrNull { parent ->
            val normalizedDramaLinks = parent.select("a[href*=$dramaPath]")
                .map { normalizeUrl(it.attr("href")) }
                .filter { it == href || it.startsWith("$href?") }

            normalizedDramaLinks.isNotEmpty() &&
                parent.selectFirst("img[src]") != null &&
                parent.select("a[href*=$dramaPath]").count { !it.attr("href").contains("/ep") } <= 3
        }
    }

    private fun extractDramaPlot(document: Document): String? {
        val heading = document.select("h2")
            .firstOrNull { it.text().contains("Plot of", true) }
            ?: return null

        return heading.nextElementSibling()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTopTags(document: Document, titleElement: Element?): List<String> {
        val topSection = titleElement?.parents()?.firstOrNull { it.select("a[href*=$categoryPath]").isNotEmpty() }
        return (topSection ?: document).select("a[href*=$categoryPath]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    private fun findHeroImage(document: Document): String? {
        return document.select("img[src]")
            .firstOrNull { image ->
                val src = image.attr("src")
                src.contains("melolo.com", true) || src.contains("minishort.com", true)
            }
            ?.attr("src")
    }

    private fun isPotentialPlot(text: String, title: String, tags: List<String>): Boolean {
        if (text.length < 40) return false
        if (text.equals(title, true)) return false
        if (tags.any { it.equals(text, true) }) return false
        return text.contains(' ') && text.any { it.isLetter() }
    }

    private fun extractEpisodeCount(text: String): Int? {
        return Regex("""(\d+)\s*Eps""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun scoreDramaCard(query: String, card: DramaCard): Int {
        val normalizedQuery = query.trim().lowercase()
        val title = card.title.lowercase()
        val plot = card.plot.orEmpty().lowercase()
        val tags = card.tags.joinToString(" ").lowercase()
        val joined = "$title $plot $tags".trim()
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }

        return when {
            title == normalizedQuery -> 120
            title.contains(normalizedQuery) -> 100
            tokens.all { title.contains(it) } -> 90
            joined.contains(normalizedQuery) -> 70
            tokens.all { joined.contains(it) } -> 50
            else -> 0
        }
    }

    private fun DramaCard.toSearchResult(): SearchResponse? {
        if (title.isBlank() || url.isBlank()) return null
        return newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = this@toSearchResult.posterUrl
        }
    }

    private fun extractDramaSlug(url: String): String? {
        return normalizeUrl(url)
            .substringAfter("/dramas/", "")
            .substringBefore("/")
            .substringBefore("?")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun extractEpisodeNumber(url: String): Int? {
        return episodeNumberRegex.find(normalizeUrl(url))
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        val absolute = when {
            trimmed.startsWith("http", true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$siteUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }

        return absolute
            .substringBefore("#")
            .substringBefore("?")
            .trimEnd('/')
    }

    private fun cleanImageUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return null

        return when {
            value.startsWith("//") -> "https:${value.substringBefore("?").substringBefore("!").trim()}"
            value.startsWith("http", true) -> value.substringBefore("?").substringBefore("!").trim()
            value.startsWith("/") -> "$siteUrl${value.substringBefore("?").substringBefore("!").trim()}"
            else -> value.substringBefore("?").substringBefore("!").trim()
        }
    }

    private fun decodeScriptValue(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    data class DramaCard(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val plot: String?,
        val tags: List<String>,
        val episodeCount: Int?,
    )
}
