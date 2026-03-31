package com.melolo

import android.util.Base64
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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    private val shortDramaSnackerRegex = Regex(
        """https?://short\.inbeidou\.ai/link/dramasnack/serial/[A-Za-z0-9]+(?:/\d+)?""",
        RegexOption.IGNORE_CASE
    )
    private val dramaSnackerEpisodeRegex = Regex(
        """https?://www\.dramasnacker\.com/episode/\d+[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )
    private val dramaSnackerBookIdRegex = Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE)
    private val dramaSnackerApiUrl = "https://api.dramasnacker.com/drama-snacker/portal/client"
    private val dramaSnackerWebUrl = "https://www.dramasnacker.com"
    private val defaultDramaSnackerChannelCode = "DSTTBD1050169"
    private val dramaSnackerCryptoKey = "aF4kZ92LmQp8xRcv"
    private val dramaSnackerCryptoIv = "gH7pK2xQz91RtMVa"
    private val dramaSnackerPrivateKeyPem = """
        -----BEGIN PRIVATE KEY-----
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDVXJp1NIw8gRJRWkcQPMv7qrSU7wyeOWVXMR2FT+G6UQw/rv6lKaRZVNiQ9EWYBdlEyQQmJJ28V9WfO/SjAh6PO36FpCYbRI/Y1tBmrWferdYjiIWj6oCydlgSGEhjQoqVwMhzeKo/SbQIYDHKRuw921PurxbWRXoF5k6gQy7TFJpBh9EyONrXN0nmz/XdCIdxhxLZMe8G/9UEdmGo8WEGKrzx6aGVGu9UXyUHUuAyh/gVpNGC8otxgJdhUxzbRc067Qu3WAIIkQKSZoJ5S/0F8LTHDYxdK6yBaIEQRbuGaQLFfacT++qzrkdPWOolX7NyABV0B+rfdkyXh4NEnh5lAgMBAAECggEBAJY6suLt0gRUGVLAzyKmvDYCt03al7bc0Pc4tQGGAnlO0eIRVGl0zay8qhQ+erYVACuHom09APd5nQeWjqUsO9o8WNS+hLpUZziV4H07gdRv8ERqvzZwSpfd7hsnj+icFLpm2H09rBoNyj7PhJ9ZmsPfJ9T64YiTuNxokloTk+e443VFFWTDFyIUKo7RDJSNJKMuaoB7PIJu4kYKcJLE2U7vHbxsWHwfeMiEhZ97kbHNqE0zegQoVI8eGXrodXKvtuBdvsU1RcfX4MYm+iNSVjn4cv4rTksoLi1pOGr1nW7fUmeTJsVPPKaDPNte0WGhSPYoB0nBEfUBGXjB702U7SECgYEA9Wo1qVDKzKe8a12VhhfJBvIGwgN2bMxpWrYQrjdoVL6KOeOSsYDvro0JDM2EHyWFBk9gc33nkYuLYo0lQ9dl7wYHIeFoXlt1mCGPtTlrRMOmLCqUbdkRob/4/62Pnkdz5jwKlPY3KG5H7iP5tytlEiGOIiwWiPDKHjlFiiAtt+0CgYEA3pB5MQS/5tLJbVnyeCCqQuwH+1E0RZD2uhmZ4pH/kbnhURnsPSPDjMKrQc5Xa+QfbnML+ps9fWTFDeZ+E7cBFa2NXvLSqHgC/Fch3GYSQJ1qFt5EfameTyOiPBwu0zsgFpRH9/HxBNWKokvVXJIk3gVF/60FQvyWAn9errBeQVkCgYBo8c8apVLjq0LWgsFjAx7S2oJrSsHEirDuunZtmYIC4ywGzzs2rpVQBj19fRDnpMq6xQzQtmFlCtBDB2qNFToguWopYdOYrfGeaZOjgndNg4C22Ep6ot14Vrhq1VRZ8eIs7TX1N0ilAGu/+SBa5LKmyzSVhlbonldAD2ueQl5qjQKBgBl8r/Q2GAfF4b09DLBHBVhukSdtkC/bPvXm0qGImJzGjY/tCQmjW9R1wojhqU84q4TJdfi36F3AuXQzDgMR9PTXkBXsdsVGIQlmrQEBS1vM6wY9Y9iEIRXs/bomfBJCdhU/29IACdrE3YBicMeOENy/+9kgpjaamE8m6N/WYKTZAoGAJIoaRu9q3TiQ6a4g6qQ249CHEO/DUh1P+wPI4/7802d5Dl+NQVE5srbmwowy4FT8H2fXN653Dbd6e9gMcB/9E6XMW1d+6XHoODmgHYHQFTmfZVfzvU+ioAeEViQ0APhon7uGX+dkyPGXuDAHrAnGfTG05EM1XeKB3QxGlxjvA1A=
        -----END PRIVATE KEY-----
    """.trimIndent()
    private val dramaSnackerMobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; 23090RA98G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "$dramaSnackerWebUrl/",
    )
    private val secureRandom = SecureRandom()
    private val visitorId = buildVisitorId()
    private val dramaSnackerPrivateKey by lazy { parsePrivateKey(dramaSnackerPrivateKeyPem) }
    private var dramaSnackerSession: DramaSnackerSession? = null

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
        val totalEpisodeCount = extractVisibleEpisodeCount(doc)
        val publicEpisodes = extractPlayableEpisodes(doc.html(), dramaSlug)
        val lastEpisodeNumber = maxOf(
            totalEpisodeCount ?: 0,
            publicEpisodes.maxOfOrNull { it.first } ?: 0
        )
        val episodes = (1..lastEpisodeNumber)
            .map { episodeNumber ->
                val episodeUrl = buildEpisodeUrl(dramaSlug, episodeNumber)
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
            buildPlotWithAvailabilityNote(
                plot = plot,
                totalEpisodeCount = totalEpisodeCount,
                publicEpisodeCount = publicEpisodes.size
            )?.let { this.plot = it }
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
        if (html.contains("NEXT_HTTP_ERROR_FALLBACK;404", true) || html.contains("Page not found", true)) {
            return false
        }
        val decodedHtml = decodeScriptValue(html)
        val hasDramaSnackerReference = shortDramaSnackerRegex.containsMatchIn(decodedHtml) ||
            dramaSnackerEpisodeRegex.containsMatchIn(decodedHtml)

        val dramaSnackerEntry = resolveDramaSnackerEntry(html, episodeNumber)
        if (dramaSnackerEntry != null && episodeNumber != null) {
            val chapterList = getDramaSnackerChapterList(
                bookId = dramaSnackerEntry.bookId,
                channelCode = dramaSnackerEntry.channelCode
            )
            if (chapterList.isNotEmpty()) {
                val targetChapter = findDramaSnackerChapterForEpisode(chapterList, episodeNumber)
                if (targetChapter != null) {
                    if ((targetChapter.isCharge ?: 0) == 1) {
                        return false
                    }

                    val streamUrl = targetChapter.chapterIdText
                        ?.let { chapterId ->
                            getDramaSnackerChapterVideo(
                                bookId = dramaSnackerEntry.bookId,
                                chapterId = chapterId,
                                chapterIndex = targetChapter.chapterIndex ?: (episodeNumber - 1),
                                channelCode = dramaSnackerEntry.channelCode
                            )
                        }

                    if (!streamUrl.isNullOrBlank()) {
                        emitExtractorLink(
                            callback = callback,
                            streamUrl = streamUrl,
                            episodeNumber = episodeNumber,
                            referer = "$dramaSnackerWebUrl/",
                            origin = "",
                            userAgent = dramaSnackerMobileHeaders.getValue("User-Agent")
                        )
                        return true
                    }

                    return false
                }
            }
        }
        if (hasDramaSnackerReference && episodeNumber != null) {
            return false
        }

        val streams = LinkedHashMap<Int, String>()
        episodeStreamRegex.findAll(html).forEach { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            val streamUrl = cleanStreamUrl(match.groupValues[2])
            if (streamUrl.isNotBlank() && !streams.containsKey(number)) {
                streams[number] = streamUrl
            }
        }

        val directUrl = episodeNumber?.let { streams[it] }
            ?: streams.values.firstOrNull()
            ?: directStreamRegex.find(html)?.value?.let(::cleanStreamUrl)

        if (directUrl.isNullOrBlank()) return false
        emitExtractorLink(
            callback = callback,
            streamUrl = directUrl,
            episodeNumber = episodeNumber,
            referer = episodeUrl,
            origin = siteUrl,
            userAgent = defaultHeaders.getValue("User-Agent")
        )

        return true
    }

    private suspend fun emitExtractorLink(
        callback: (ExtractorLink) -> Unit,
        streamUrl: String,
        episodeNumber: Int?,
        referer: String,
        origin: String,
        userAgent: String
    ) {
        val linkType = when {
            streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${episodeNumber?.let { "Episode $it" } ?: "Stream"}",
                url = streamUrl,
                type = linkType
            ) {
                quality = Qualities.Unknown.value
                this.referer = referer
                headers = buildMap {
                    put("Accept", "*/*")
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (origin.isNotBlank()) put("Origin", origin)
                    put("User-Agent", userAgent)
                }
            }
        )
    }

    private fun findDramaSnackerChapterForEpisode(
        chapterList: List<DramaSnackerChapter>,
        episodeNumber: Int
    ): DramaSnackerChapter? {
        if (chapterList.isEmpty() || episodeNumber <= 0) return null

        val indexedChapters = chapterList
            .mapNotNull { chapter ->
                val index = chapter.chapterIndex ?: return@mapNotNull null
                chapter to index
            }
            .sortedBy { it.second }
        if (indexedChapters.isEmpty()) return null

        val usesZeroBasedIndex = indexedChapters.any { it.second == 0 }
        val expectedIndex = if (usesZeroBasedIndex) episodeNumber - 1 else episodeNumber

        return indexedChapters.firstOrNull { it.second == expectedIndex }?.first
            ?: indexedChapters.getOrNull((episodeNumber - 1).coerceAtLeast(0))?.first
            ?: indexedChapters.lastOrNull()?.first
    }

    private suspend fun resolveDramaSnackerEntry(html: String, episodeNumber: Int?): DramaSnackerEntry? {
        val decodedHtml = decodeScriptValue(html)
        val directDramaSnacker = dramaSnackerEpisodeRegex.find(decodedHtml)?.value
        val shortUrls = shortDramaSnackerRegex.findAll(decodedHtml)
            .map { it.value }
            .distinct()
            .toList()
        val resolvedUrl = directDramaSnacker
            ?: shortUrls.firstNotNullOfOrNull { shortUrl ->
                resolveShortRedirect(shortUrl)
            }
            ?: shortUrls.firstNotNullOfOrNull { shortUrl ->
                runCatching {
                    val response = app.get(shortUrl, headers = dramaSnackerMobileHeaders)
                    dramaSnackerEpisodeRegex.find(response.text)?.value
                }.getOrNull()
            }

        val normalizedResolvedUrl = resolvedUrl
            ?.let(::cleanStreamUrl)
            ?.takeIf { it.contains("/episode/", true) }
            ?: return null
        val bookId = dramaSnackerBookIdRegex.find(normalizedResolvedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val channelCode = extractQueryParam(normalizedResolvedUrl, "chid")

        return DramaSnackerEntry(
            bookId = bookId,
            channelCode = channelCode,
            referer = "$dramaSnackerWebUrl/episode/$bookId",
            episodeNumber = episodeNumber
        )
    }

    private suspend fun resolveShortRedirect(url: String): String? {
        val response = runCatching {
            app.get(url, headers = dramaSnackerMobileHeaders)
        }.getOrNull() ?: return null

        val redirectedUrl = cleanStreamUrl(response.url)
            .takeIf { it.contains("/episode/", true) }
        if (redirectedUrl != null) return redirectedUrl

        return dramaSnackerEpisodeRegex.find(response.text)
            ?.value
            ?.let(::cleanStreamUrl)
            ?.takeIf { it.contains("/episode/", true) }
    }

    private suspend fun getDramaSnackerChapterList(
        bookId: String,
        channelCode: String?
    ): List<DramaSnackerChapter> {
        val body = mapOf("bookId" to bookId).toJson()
        return requestDramaSnackerData(
            endpoint = "/content/chapter/list",
            body = body,
            channelCode = channelCode
        )?.chapterList.orEmpty()
    }

    private suspend fun getDramaSnackerChapterVideo(
        bookId: String,
        chapterId: String,
        chapterIndex: Int,
        channelCode: String?
    ): String? {
        val playSources = listOf("drama_snacker_manual", "drama_snacker")
        for (playSource in playSources) {
            val streamUrl = requestDramaSnackerChapterVideo(
                bookId = bookId,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                channelCode = channelCode,
                playSource = playSource
            )?.let(::cleanStreamUrl)
                ?.takeIf { it.isNotBlank() }
                ?: continue

            if (isDramaSnackerVideoUrlValid(streamUrl)) {
                return streamUrl
            }
        }
        return null
    }

    private suspend fun requestDramaSnackerChapterVideo(
        bookId: String,
        chapterId: String,
        chapterIndex: Int,
        channelCode: String?,
        playSource: String
    ): String? {
        val body = linkedMapOf<String, Any>(
            "bookId" to bookId,
            "chapterId" to chapterId,
            "chapterIndex" to chapterIndex,
            "currencyPlaySource" to playSource
        ).toJson()

        return requestDramaSnackerData(
            endpoint = "/content/chapter/load",
            body = body,
            channelCode = channelCode
        )?.chapterVo?.preferredVideoPath
    }

    private suspend fun isDramaSnackerVideoUrlValid(streamUrl: String): Boolean {
        return runCatching {
            val probeHeaders = mapOf(
                "Accept" to "*/*",
                "User-Agent" to dramaSnackerMobileHeaders.getValue("User-Agent"),
                "Referer" to "$dramaSnackerWebUrl/",
                "Range" to "bytes=0-1",
            )
            app.get(streamUrl, headers = probeHeaders).code in 200..299
        }.getOrDefault(false)
    }

    private suspend fun ensureDramaSnackerSession(channelCode: String?): DramaSnackerSession? {
        dramaSnackerSession
            ?.takeIf { it.userId.isNotBlank() && it.token.isNotBlank() }
            ?.let { return it }

        val registerEnvelope = requestDramaSnackerEnvelope(
            endpoint = "/user/register",
            body = "{}",
            channelCode = channelCode,
            session = null
        ) ?: return null
        if (registerEnvelope.status != 0) return null

        val registerData = registerEnvelope.data
            ?.toJson()
            ?.let { tryParseJson<DramaSnackerRegisterData>(it) }
            ?: return null
        val session = DramaSnackerSession(
            userId = registerData.userIdText ?: "",
            token = registerData.token?.trim().orEmpty()
        )
        if (session.userId.isBlank() || session.token.isBlank()) return null

        dramaSnackerSession = session
        return session
    }

    private suspend fun requestDramaSnackerData(
        endpoint: String,
        body: String,
        channelCode: String?
    ): DramaSnackerData? {
        var session = ensureDramaSnackerSession(channelCode) ?: return null
        var envelope = requestDramaSnackerEnvelope(
            endpoint = endpoint,
            body = body,
            channelCode = channelCode,
            session = session
        ) ?: return null
        if (envelope.status == 10) {
            dramaSnackerSession = null
            session = ensureDramaSnackerSession(channelCode) ?: return null
            envelope = requestDramaSnackerEnvelope(
                endpoint = endpoint,
                body = body,
                channelCode = channelCode,
                session = session
            ) ?: return null
        }
        if (envelope.status != 0) return null

        return envelope.data
            ?.toJson()
            ?.let { tryParseJson<DramaSnackerData>(it) }
    }

    private suspend fun requestDramaSnackerEnvelope(
        endpoint: String,
        body: String,
        channelCode: String?,
        session: DramaSnackerSession?
    ): DramaSnackerEnvelope? {
        val webParams = buildDramaSnackerWebParams(
            body = body,
            channelCode = channelCode,
            session = session
        ) ?: return null
        val response = app.post(
            "$dramaSnackerApiUrl$endpoint",
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json, text/plain, */*",
                "Origin" to dramaSnackerWebUrl,
                "Referer" to "$dramaSnackerWebUrl/",
                "User-Agent" to dramaSnackerMobileHeaders.getValue("User-Agent"),
                "webParams" to webParams,
            )
        )
        return parseDramaSnackerEnvelope(response.text)
    }

    private fun parseDramaSnackerEnvelope(rawBody: String): DramaSnackerEnvelope? {
        val rawText = rawBody.trim()
        if (rawText.isBlank()) return null

        val jsonEnvelope = tryParseJson<DramaSnackerEnvelope>(rawText)
        if (jsonEnvelope?.status != null || jsonEnvelope?.data != null) {
            return jsonEnvelope
        }

        val encryptedPayload = tryParseJson<String>(rawText)
            ?.takeIf { it.isNotBlank() }
            ?: rawText.removePrefix("\"").removeSuffix("\"")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .trim()
        if (encryptedPayload.isBlank()) return null

        val decryptedBody = decryptDramaSnackerPayload(encryptedPayload) ?: return null
        return tryParseJson<DramaSnackerEnvelope>(decryptedBody)
    }

    private fun buildDramaSnackerWebParams(
        body: String,
        channelCode: String?,
        session: DramaSnackerSession?
    ): String? {
        val token = session?.token.orEmpty()
        val userId = session?.userId.orEmpty()
        val sign = signDramaSnacker("$visitorId$token$body")
        val params = linkedMapOf<String, Any>(
            "plineEnum" to "DRAMASNACKER",
            "platform" to "SNACKER",
            "os" to "android",
            "deviceId" to visitorId,
            "currentLanguage" to "in",
            "language" to "id-ID",
            "userId" to userId,
            "token" to token,
            "sign" to sign,
            "localTime" to getCurrentLocalTime(),
            "timeZoneOffset" to getCurrentTimeZoneOffset(),
        )
        params["channelCode"] = channelCode?.takeIf { it.isNotBlank() } ?: defaultDramaSnackerChannelCode

        return encryptDramaSnackerPayload(params.toJson())
    }

    private fun encryptDramaSnackerPayload(payload: String): String? {
        return runCatching {
            val gzippedBytes = gzip(payload.toByteArray(Charsets.UTF_8))
            val payloadBase64 = Base64.encodeToString(gzippedBytes, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(dramaSnackerCryptoKey.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(dramaSnackerCryptoIv.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            Base64.encodeToString(cipher.doFinal(payloadBase64.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decryptDramaSnackerPayload(payload: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(dramaSnackerCryptoKey.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(dramaSnackerCryptoIv.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val cipherBytes = Base64.decode(normalizeBase64(payload), Base64.DEFAULT)
            val encodedGzip = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            val gzippedBytes = Base64.decode(normalizeBase64(encodedGzip), Base64.DEFAULT)
            String(ungzip(gzippedBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun signDramaSnacker(payload: String): String {
        return runCatching {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(dramaSnackerPrivateKey)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
        }.getOrDefault(payload)
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val normalized = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val keyBytes = Base64.decode(normalized, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { it.write(input) }
        return outputStream.toByteArray()
    }

    private fun ungzip(input: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
    }

    private fun normalizeBase64(value: String): String {
        return value
            .replace('-', '+')
            .replace('_', '/')
            .replace('.', '=')
            .replace(Regex("[^A-Za-z0-9+/=]"), "")
    }

    private fun buildVisitorId(): String {
        val millis36 = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val randomPart = buildString {
            repeat(8) {
                append("abcdefghijklmnopqrstuvwxyz0123456789"[secureRandom.nextInt(36)])
            }
        }
        return "visitor_${millis36}_$randomPart"
    }

    private fun getCurrentLocalTime(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date())
    }

    private fun getCurrentTimeZoneOffset(): String {
        val totalMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
        val sign = if (totalMinutes >= 0) "+" else "-"
        val absoluteMinutes = kotlin.math.abs(totalMinutes)
        val hours = absoluteMinutes / 60
        val minutes = absoluteMinutes % 60
        return "$sign${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
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
                if (!cards.containsKey(card.url)) {
                    cards[card.url] = card
                }
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

    private fun extractVisibleEpisodeCount(document: Document): Int? {
        val html = document.html()
        return Regex("""Semua Episode \((\d+)\)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex(""">(\d+)<!-- -->\s*Eps<""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: extractEpisodeCount(document.text())
    }

    private fun extractPlayableEpisodes(html: String, dramaSlug: String): List<Pair<Int, String>> {
        val numbers = LinkedHashSet<Int>()

        episodeStreamRegex.findAll(html).forEach { match ->
            val episodeNumber = match.groupValues[1].toIntOrNull() ?: return@forEach
            val streamUrl = cleanStreamUrl(match.groupValues[2])
            if (streamUrl.isNotBlank()) {
                numbers.add(episodeNumber)
            }
        }

        Regex("""href="([^"]*/dramas/${Regex.escape(dramaSlug)}/ep(\d+)[^"]*)"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val href = normalizeUrl(match.groupValues[1])
                val episodeNumber = extractEpisodeNumber(href) ?: return@forEach
                numbers.add(episodeNumber)
            }

        return numbers
            .sorted()
            .map { episodeNumber -> episodeNumber to buildEpisodeUrl(dramaSlug, episodeNumber) }
    }

    private fun buildEpisodeUrl(dramaSlug: String, episodeNumber: Int): String {
        return "$mainUrl/dramas/$dramaSlug/ep$episodeNumber"
    }

    private fun buildPlotWithAvailabilityNote(
        plot: String?,
        totalEpisodeCount: Int?,
        publicEpisodeCount: Int
    ): String? {
        val normalizedPlot = plot?.trim()?.takeIf { it.isNotBlank() }
        if (totalEpisodeCount == null || totalEpisodeCount <= publicEpisodeCount) {
            return normalizedPlot
        }

        val availabilityNote =
            "Catatan: Melolo menampilkan $totalEpisodeCount episode, tetapi saat ini hanya $publicEpisodeCount episode yang memiliki halaman dan stream web publik. Episode lainnya masih kosong di web atau diarahkan ke aplikasi Melolo."

        return listOfNotNull(normalizedPlot, availabilityNote)
            .joinToString("\n\n")
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

    private fun extractQueryParam(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null

        return query.split("&")
            .firstNotNullOfOrNull { part ->
                val separatorIndex = part.indexOf('=')
                if (separatorIndex <= 0) return@firstNotNullOfOrNull null
                val queryKey = part.substring(0, separatorIndex)
                if (!queryKey.equals(key, ignoreCase = true)) return@firstNotNullOfOrNull null
                val value = part.substring(separatorIndex + 1).trim()
                value.takeIf { it.isNotBlank() }
            }
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

    private fun cleanStreamUrl(value: String): String {
        return decodeScriptValue(value)
            .trim()
            .trimEnd('\\')
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

    data class DramaSnackerEntry(
        val bookId: String,
        val channelCode: String?,
        val referer: String,
        val episodeNumber: Int?,
    )

    data class DramaSnackerEnvelope(
        val status: Int? = null,
        val message: String? = null,
        val data: Any? = null,
    )

    data class DramaSnackerSession(
        val userId: String,
        val token: String,
    )

    data class DramaSnackerRegisterData(
        val userId: Any? = null,
        val token: String? = null,
    ) {
        val userIdText: String?
            get() = userId?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    data class DramaSnackerData(
        val chapterList: List<DramaSnackerChapter>? = null,
        val chapterVo: DramaSnackerChapterVo? = null,
    )

    data class DramaSnackerChapter(
        val chapterId: Any? = null,
        val chapterIndex: Int? = null,
        val isCharge: Int? = null,
    ) {
        val chapterIdText: String?
            get() = chapterId?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    data class DramaSnackerChapterVo(
        val chapterId: Any? = null,
        val chapterIndex: Int? = null,
        val videoPath: String? = null,
        val videoUrl: String? = null,
        val m3u8Url: String? = null,
        val streamUrl: String? = null,
        val isCharge: Int? = null,
    ) {
        val preferredVideoPath: String?
            get() = sequenceOf(videoPath, videoUrl, m3u8Url, streamUrl)
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }
    }
}
