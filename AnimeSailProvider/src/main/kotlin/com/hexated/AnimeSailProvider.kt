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
    override var name = "AnimeSail🍷"
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
            headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"),
            cookies = mapOf("_as_ipin_ct" to "ID"),
            referer = ref,
            interceptor = turnstileInterceptor
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/genres/donghua/page/" to "Donghua"
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
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".tt > h2").text().trim()
        val posterUrl = fixUrlNull(this.selectFirst("div.limit img")?.attr("src"))
        val epNum = this.selectFirst(".tt > h2")?.text()?.let {
            Regex("Episode\\s?(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
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
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = iframe,
                                type = if (iframe.endsWith(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                referer = mainUrl
                                this.quality = quality
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
                        this.referer = referer ?: mainUrl
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

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

}

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {
    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()

        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        var currentCookies = cookieManager.getCookie(domainUrl) ?: ""
        var userAgent = originalRequest.header("User-Agent") ?: ""

        var needsRefresh = false
        var initialResponse: Response? = null

        if (currentCookies.contains(targetCookie)) {
            val requestBuilder = originalRequest.newBuilder()
                .header("Cookie", currentCookies)

            initialResponse = chain.proceed(requestBuilder.build())

            if (initialResponse.code == 403 || initialResponse.code == 503) {
                needsRefresh = true
                initialResponse.close()
            } else {
                return initialResponse
            }
        } else {
            needsRefresh = true
        }

        if (needsRefresh) {
            val context = AcraApplication.context
            if (context != null) {
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null

                handler.post {
                    try {
                        val newWebView = WebView(context)
                        webView = newWebView

                        newWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = userAgent.ifBlank { userAgentString }
                        }
                        userAgent = newWebView.settings.userAgentString ?: userAgent

                        newWebView.webViewClient = WebViewClient()

                        cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0")
                        cookieManager.flush()

                        newWebView.loadUrl(url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var attempts = 0
                val maxAttempts = 15
                while (attempts < maxAttempts) {
                    Thread.sleep(1000)
                    val checkCookies = cookieManager.getCookie(domainUrl) ?: ""

                    if (checkCookies.contains(targetCookie)) {
                        cookieManager.flush()
                        break
                    }
                    attempts++
                }

                handler.post {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            currentCookies = cookieManager.getCookie(domainUrl) ?: ""
            val newRequestBuilder = originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .header("Cookie", currentCookies)

            return chain.proceed(newRequestBuilder.build())
        }

        return initialResponse ?: chain.proceed(originalRequest)
    }
}
