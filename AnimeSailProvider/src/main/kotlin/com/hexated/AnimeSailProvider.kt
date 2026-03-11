package com.hexated

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    private val turnstileInterceptor = TurnstileInterceptor()

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore(
                    "-episode"
                )

                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val rawHref = fixUrlNull(this.selectFirst("a")?.attr("href")).toString()
        val href = getProperAnimeLink(rawHref)
        val rawTitle = this.selectFirst(".tt > h2")?.text() ?: ""
        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))
        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val typeText = this.selectFirst(".tt > span")?.text() ?: ""
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = request(link).document

        return document.select("div.listupd article").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString()
            .replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()
            val episode =
                Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"

        document.select(".mobius > .mirror > option").amap { element ->
            safeApiCall {
                val encodedData = element.attr("data-em")
                if (encodedData.isBlank()) return@safeApiCall

                val iframeSrc = decodeIframeFromDataEm(encodedData) ?: return@safeApiCall
                val iframe = fixUrl(iframeSrc)
                if (iframe.contains("statistic") || iframe.isBlank()) return@safeApiCall

                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)

                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                when {
                    iframe.endsWith(".mp4", ignoreCase = true) || iframe.endsWith(".m3u8", ignoreCase = true) -> {
                        val isMp4UploadDirect = iframe.contains("mp4upload.com", ignoreCase = true)
                        val directReferer = if (isMp4UploadDirect) "https://www.mp4upload.com/" else mainUrl
                        val directHeaders = if (isMp4UploadDirect) {
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to directReferer,
                                "Origin" to "https://www.mp4upload.com"
                            )
                        } else {
                            emptyMap()
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = iframe,
                                type = if (iframe.endsWith(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                referer = directReferer
                                this.quality = quality
                                this.headers = directHeaders
                            }
                        )
                    }

                    iframe.contains("${playerPath}popup") -> {
                        val encodedUrl = iframe.substringAfter("url=").substringBefore("&")
                        if (encodedUrl.isNotBlank()) {
                            val realUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                            loadFixedExtractor(realUrl, serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    iframe.contains("player-kodir.aghanim.xyz") || iframe.contains("${playerPath}kodir2") -> {
                        val res = request(iframe, ref = data).text
                        var link = Jsoup.parse(res.substringAfter("= `", "").substringBefore("`;", ""))
                            .select("source").last()?.attr("src")

                        if (link.isNullOrBlank()) {
                            link = Jsoup.parse(res).select("source").attr("src")
                        }

                        if (!link.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = serverName,
                                    name = serverName,
                                    url = link,
                                    type = INFER_TYPE
                                ) {
                                    referer = iframe
                                    this.quality = quality
                                }
                            )
                        }
                    }

                    iframe.contains("${playerPath}framezilla") || iframe.contains("uservideo.xyz") -> {
                        val doc = request(iframe, ref = data).document
                        val innerLink = doc.select("iframe").attr("src")
                        if (innerLink.isNotBlank()) {
                            loadFixedExtractor(fixUrl(innerLink), serverName, quality, mainUrl, subtitleCallback, callback)
                        }
                    }

                    iframe.contains("aghanim.xyz/tools/redirect/") -> {
                        val id = iframe.substringAfter("id=").substringBefore("&token")
                        val link = "https://rasa-cintaku-semakin-berantai.xyz/v/$id"
                        loadFixedExtractor(link, serverName, quality, mainUrl, subtitleCallback, callback)
                    }

                    iframe.contains(playerPath) -> {
                        val doc = request(iframe, ref = data).document
                        val link = doc.select("source").attr("src")
                        if (link.isNotBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = serverName,
                                    name = serverName,
                                    url = link,
                                    type = INFER_TYPE
                                ) {
                                    referer = iframe
                                    this.quality = quality
                                }
                            )
                        }
                    }

                    else -> {
                        loadFixedExtractor(iframe, serverName, quality, mainUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    private fun decodeIframeFromDataEm(encodedData: String): String? {
        val decoded = runCatching { base64Decode(encodedData.trim()) }.getOrNull() ?: return null
        return Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tryLoadMp4UploadDirect(url, serverName, quality, callback)) return

        loadExtractor(url, referer, subtitleCallback) { link ->
            val finalName =
                if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"

            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = finalName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer ?: mainUrl
                        this.quality =
                            if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                                ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private suspend fun tryLoadMp4UploadDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val downloadUrl = "https://www.mp4upload.com/dl?op=download2&id=$id"
        val watchReferer = "https://www.mp4upload.com/"
        val redirect = runCatching {
            app.get(
                downloadUrl,
                referer = watchReferer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            )
        }.getOrNull() ?: return false

        val location = redirect.headers["Location"] ?: redirect.headers["location"]
        val finalUrl = when {
            location.isNullOrBlank() -> return false
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> "https://www.mp4upload.com$location"
            else -> return false
        }

        val probe = runCatching {
            app.get(
                finalUrl,
                referer = watchReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com",
                    "Range" to "bytes=0-4095"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (!(contentType.contains("octet-stream") || contentType.contains("video"))) return false

        callback.invoke(
            newExtractorLink(
                source = "Mp4Upload",
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                this.referer = watchReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            }
        )
        return true
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

}

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {
    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 30
    }

    private fun getCookieValue(domainUrl: String): String? {
        val raw = CookieManager.getInstance().getCookie(domainUrl) ?: return null
        return raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$targetCookie=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun invalidateCookie(domainUrl: String) {
        CookieManager.getInstance().apply {
            setCookie(domainUrl, "$targetCookie=; Max-Age=0")
            flush()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()
        if (getCookieValue(domainUrl) != null) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", cookieManager.getCookie(domainUrl) ?: "")
                    .build()
            )
            if (response.code != 403 && response.code != 503) return response
            response.close()
            invalidateCookie(domainUrl)
        }

        val context = AcraApplication.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var resolvedUserAgent = originalRequest.header("User-Agent") ?: ""

        handler.post {
            try {
                val wv = WebView(context).also { webView = it }
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    if (resolvedUserAgent.isNotBlank()) userAgentString = resolvedUserAgent
                    resolvedUserAgent = userAgentString
                }
                wv.webViewClient = WebViewClient()
                wv.loadUrl(url)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            Thread.sleep(POLL_INTERVAL_MS)
            if (getCookieValue(domainUrl) != null) {
                cookieManager.flush()
                break
            }
            attempts++
        }

        handler.post {
            try {
                webView?.apply {
                    stopLoading()
                    clearCache(false)
                    destroy()
                }
                webView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        return chain.proceed(
            originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .apply { if (resolvedUserAgent.isNotBlank()) header("User-Agent", resolvedUserAgent) }
                .build()
        )
    }
}
