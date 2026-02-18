package com.pusatfilm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

private data class KotakajaibApiResponse(
    val result: KotakajaibResult? = null,
)

private data class KotakajaibResult(
    val mirrors: List<KotakajaibMirror>? = null,
)

private data class KotakajaibMirror(
    val server: String? = null,
    val resolution: List<Int>? = null,
)

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
        // Some hosts (e.g. emturbovid) are sensitive to referer. When we are parsing an embed page,
        // keep the embed URL as our referer baseline.
        val pageReferer = when {
            fixedUrl.contains("/embed/") -> fixedUrl
            else -> referer ?: "$mainUrl/"
        }

        when {
            fixedUrl.contains("/api/file/") && fixedUrl.contains("/download") -> {
                parseApi(fixedUrl, pageReferer, subtitleCallback, callback)
            }

            fixedUrl.contains("/mirror/") -> {
                resolveMirror(fixedUrl, pageReferer, null, subtitleCallback, callback)
            }

            fixedUrl.contains("/file/") -> {
                val fileId = fixedUrl.substringAfter("/file/").substringBefore("/").substringBefore("?")
                if (fileId.isNotBlank()) {
                    parseApi("$mainUrl/api/file/$fileId/download", "$mainUrl/file/$fileId", subtitleCallback, callback)
                }
                parsePage(fixedUrl, pageReferer, subtitleCallback, callback)
            }

            else -> parsePage(fixedUrl, pageReferer, subtitleCallback, callback)
        }
    }

    private suspend fun parseApi(
        apiUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = apiUrl.substringAfter("/api/file/").substringBefore("/").substringBefore("?")
        if (fileId.isBlank()) return

        val apiJson = runCatching {
            app.get(
                apiUrl,
                referer = referer,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                )
            ).text
        }.getOrNull() ?: return

        val payload = tryParseJson<KotakajaibApiResponse>(apiJson)?.result ?: return
        val mirrors = payload.mirrors.orEmpty()

        mirrors.forEach { mirror ->
            val server = mirror.server?.trim()?.lowercase().orEmpty()
            if (server.isBlank()) return@forEach

            val resolutions = mirror.resolution.orEmpty().distinct().sortedDescending()
            if (resolutions.isEmpty()) {
                resolveMirror(
                    "$mainUrl/mirror/$server/$fileId",
                    "$mainUrl/file/$fileId",
                    null,
                    subtitleCallback,
                    callback
                )
            } else {
                resolutions.forEach { res ->
                    resolveMirror(
                        "$mainUrl/mirror/$server/$fileId/$res",
                        "$mainUrl/file/$fileId",
                        res,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }

    private suspend fun parsePage(
        pageUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = runCatching { app.get(pageUrl, referer = referer).document }.getOrNull() ?: return
        val visited = linkedSetOf<String>()

        suspend fun parseTarget(raw: String?, quality: Int? = null) {
            val target = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
            val normalized = when {
                target.startsWith("http") -> target
                target.startsWith("//") -> "https:$target"
                else -> "$mainUrl/${target.trimStart('/')}"
            }
            // Skip obvious non-video assets to avoid wasting extractor attempts.
            if (Regex("""\.(css|js|png|jpg|jpeg|gif|svg|ico|webp|woff2?|ttf|otf|map)(\?|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)
            ) return
            if (!visited.add(normalized)) return

            if (normalized.contains("/api/file/") && normalized.contains("/download")) {
                parseApi(normalized, referer, subtitleCallback, callback)
                return
            }
            if (normalized.contains("/mirror/")) {
                resolveMirror(normalized, referer, quality, subtitleCallback, callback)
                return
            }
            if (normalized.contains("/file/")) {
                val fileId = normalized.substringAfter("/file/").substringBefore("/").substringBefore("?")
                if (fileId.isNotBlank()) {
                    parseApi("$mainUrl/api/file/$fileId/download", "$mainUrl/file/$fileId", subtitleCallback, callback)
                }
            }

            emitOrExtract(normalized, referer, quality, subtitleCallback, callback)
        }

        document.select("ul#dropdown-server li a[data-frame], a[data-frame]").forEach { a ->
            parseTarget(runCatching { base64Decode(a.attr("data-frame")) }.getOrNull())
        }

        // /embed/{id} pages use buttons with base64 payloads (multi-server selector)
        document.select("button.server-item[data-frame], button.server-item[data-url], button.server-item[data-src]").forEach { btn ->
            val raw =
                btn.attr("data-frame").takeIf { it.isNotBlank() }
                    ?: btn.attr("data-url").takeIf { it.isNotBlank() }
                    ?: btn.attr("data-src").takeIf { it.isNotBlank() }
            val decoded = runCatching { base64Decode(raw ?: "") }.getOrNull()
            parseTarget(decoded ?: raw)
        }

        document.select("a[href*='/api/file/'][href*='/download'], a[href*='/mirror/'], a[href*='/file/']").forEach { a ->
            parseTarget(a.attr("href"))
        }

        document.select("iframe[src], source[src], video source[src], a[href]").forEach { el ->
            parseTarget(el.attr(if (el.tagName() == "a") "href" else "src"))
        }
    }

    private suspend fun resolveMirror(
        mirrorUrl: String,
        referer: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                mirrorUrl,
                referer = referer,
                allowRedirects = false,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
        }.getOrNull() ?: return

        val location = response.headers["Location"] ?: response.headers["location"]
        if (!location.isNullOrBlank()) {
            val target = when {
                location.startsWith("http") -> location
                location.startsWith("//") -> "https:$location"
                else -> "$mainUrl/${location.trimStart('/')}"
            }

            // Some mirrors redirect to intermediate pages. We only follow if the response itself
            // provides a direct target URL (e.g. meta-refresh or a plain link), without solving challenges.
            val maybeResolved = resolveRedirectPageIfDirect(target, mirrorUrl)
            emitOrExtract(maybeResolved ?: target, referer, quality, subtitleCallback, callback)
            return
        }

        runCatching {
            response.document.select("a[href], iframe[src], source[src]").forEach { el ->
                val target = if (el.tagName() == "a") el.attr("href") else el.attr("src")
                if (target.isNotBlank()) {
                    val maybeResolved = resolveRedirectPageIfDirect(target, mirrorUrl)
                    emitOrExtract(maybeResolved ?: target, referer, quality, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun resolveRedirectPageIfDirect(url: String, referer: String): String? {
        // Only attempt for common intermediate links. If a page requires interaction, we bail out.
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        if (host.isBlank()) return null
        val lowerHost = host.lowercase()
        val mightBeIntermediate = lowerHost.contains("ouo.") || lowerHost.contains("ouo-") || lowerHost.contains("ouo")
        if (!mightBeIntermediate) return null

        val resp = runCatching { app.get(url, referer = referer, allowRedirects = false) }.getOrNull() ?: return null
        val html = runCatching { resp.text }.getOrNull()?.trim().orEmpty()
        if (html.isBlank()) return null

        // If it looks like an interaction page, don't proceed.
        if (html.contains("I'M A HUMAN", ignoreCase = true) ||
            html.contains("I am human", ignoreCase = true) ||
            html.contains("captcha", ignoreCase = true) ||
            html.contains("g-recaptcha", ignoreCase = true)
        ) return null

        // meta refresh: <meta http-equiv="refresh" content="1;url=https://...">
        Regex("""http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["'][^"']*url\s*=\s*([^"'>\s]+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        // plain link in body
        Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        return null
    }

    private suspend fun emitOrExtract(
        targetUrl: String,
        referer: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = when {
            targetUrl.startsWith("http") -> targetUrl
            targetUrl.startsWith("//") -> "https:$targetUrl"
            else -> "$mainUrl/${targetUrl.trimStart('/')}"
        }

        var handled = false

        // Pixeldrain pages are not always handled by loadExtractor in all builds.
        // If we can map /u/{id} -> /api/file/{id}, emit it directly.
        if (runCatching { java.net.URI(fixed).host?.lowercase() }.getOrNull() == "pixeldrain.com") {
            val idFromPath = Regex("""/u/([A-Za-z0-9]+)""").find(fixed)?.groupValues?.getOrNull(1)
            val id = idFromPath ?: runCatching {
                val doc = app.get(fixed, referer = referer).document
                doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
                    ?.attr("content")
                    ?.substringAfter("/api/file/")
                    ?.substringBefore("?")
                    ?.substringBefore("/")
            }.getOrNull()
            if (!id.isNullOrBlank()) {
                handled = true
                callback.invoke(
                    newExtractorLink(
                        source = "Pixeldrain",
                        name = if (quality != null) "Pixeldrain ${quality}p" else "Pixeldrain",
                        url = "https://pixeldrain.com/api/file/$id"
                    ) {
                        this.referer = "https://pixeldrain.com/"
                        this.quality = quality ?: Qualities.Unknown.value
                    }
                )
                return
            }
        }

        loadExtractor(fixed, referer, subtitleCallback) {
            handled = true
            callback(it)
        }

        if (!handled && Regex("""\.(m3u8|mp4|mkv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(fixed)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (quality != null) "$name ${quality}p" else name,
                    url = fixed
                ) {
                    this.referer = referer
                    this.quality = quality ?: getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
                }
            )
        }
    }
}

class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}
