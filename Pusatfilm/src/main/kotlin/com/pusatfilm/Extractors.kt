package com.pusatfilm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

private data class CryptoJsAesJson(
    val ct: String? = null,
    val s: String? = null, // hex salt
    val iv: String? = null, // hex iv (not used in password mode)
)

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim().removePrefix("0x")
    require(clean.length % 2 == 0) { "Invalid hex length" }
    val out = ByteArray(clean.length / 2)
    var i = 0
    while (i < clean.length) {
        out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
        i += 2
    }
    return out
}

// OpenSSL EVP_BytesToKey with MD5 (CryptoJS password-based AES default).
private fun evpBytesToKeyMd5(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
    val totalLen = keyLen + ivLen
    val out = ByteArray(totalLen)
    var offset = 0
    var prev = ByteArray(0)
    val md5 = MessageDigest.getInstance("MD5")
    while (offset < totalLen) {
        md5.reset()
        if (prev.isNotEmpty()) md5.update(prev)
        md5.update(password)
        md5.update(salt)
        prev = md5.digest()
        val toCopy = minOf(prev.size, totalLen - offset)
        System.arraycopy(prev, 0, out, offset, toCopy)
        offset += toCopy
    }
    return out.copyOfRange(0, keyLen) to out.copyOfRange(keyLen, totalLen)
}

private fun cryptoJsPasswordDecrypt(payload: CryptoJsAesJson, password: String): String? {
    val ctB64 = payload.ct?.trim().orEmpty()
    val saltHex = payload.s?.trim().orEmpty()
    if (ctB64.isBlank() || saltHex.isBlank()) return null

    val cipherText = runCatching { java.util.Base64.getDecoder().decode(ctB64) }.getOrNull() ?: return null
    val salt = runCatching { hexToBytes(saltHex) }.getOrNull() ?: return null
    val (key, iv) = evpBytesToKeyMd5(password.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val plain = runCatching { cipher.doFinal(cipherText) }.getOrNull() ?: return null
    return runCatching { String(plain, StandardCharsets.UTF_8) }.getOrNull()
}

private fun decodeFromCharCodePayload(payload: String): String {
    val sb = StringBuilder()
    Regex("""\d+""").findAll(payload).forEach { m ->
        val code = m.value.toIntOrNull() ?: return@forEach
        sb.append(code.toChar())
    }
    return sb.toString()
}

private fun extractPassFromFromCharCode(html: String): String? {
    // The page often contains 1-2 huge fromCharCode payloads. Decode the longest ones first.
    val payloads = Regex("""'([0-9A-Za-z]{200,})'""").findAll(html).map { it.groupValues[1] }.toList()
        .sortedByDescending { it.length }

    for (p in payloads) {
        val decoded = decodeFromCharCodePayload(p)
        val pass = Regex("""\bpass\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(decoded)?.groupValues?.getOrNull(1)
        if (!pass.isNullOrBlank()) return pass
    }
    return null
}

private fun extractCryptoJsDataJsonFromPackedScripts(documentHtml: String): String? {
    // Unpack each eval(p,a,c,k,e,d) script until we find: data = '{"ct":...}'
    val scripts = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        .findAll(documentHtml)
        .map { it.groupValues[1] }
        .filter { it.contains("eval(function(p,a,c,k,e,d)") }
        .toList()

    for (s in scripts) {
        val unpacked = runCatching { getAndUnpack(s) }.getOrNull() ?: continue
        // data='{"ct":"...","iv":"...","s":"..."}'
        val raw = Regex("""\bdata\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(unpacked)?.groupValues?.getOrNull(1)
            ?.takeIf { it.contains("ct") && it.contains("s") }
            ?: continue

        // The data string is frequently escaped like {\"ct\":\"...\"}
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return null
}

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

    private fun baseOrigin(url: String?): String? = runCatching {
        if (url.isNullOrBlank()) return@runCatching null
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return@runCatching null
        val host = u.host ?: return@runCatching null
        "$scheme://$host/"
    }.getOrNull()

    private fun parseGdriveVariants(master: String, baseUrl: String): List<Pair<String, Int>> {
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = ArrayList<Pair<String, Int>>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val next = lines.getOrNull(i + 1) ?: continue

            val qFromName = Regex("""NAME\s*=\s*"(\d{3,4})p"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qFromRes = Regex("""RESOLUTION\s*=\s*\d+\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qFromType = Regex("""[?&]type=(\d{3,4})""", RegexOption.IGNORE_CASE)
                .find(next)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val quality = qFromName ?: qFromRes ?: qFromType ?: Qualities.Unknown.value
            val abs = runCatching { java.net.URI(baseUrl).resolve(next).toString() }.getOrNull() ?: continue
            out.add(abs to quality)
        }
        return out
    }

    private suspend fun tryExtractGdriveplayer(
        embedUrl: String,
        referer: String,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = baseOrigin(referer) ?: "https://kotakajaib.me/"

        // gdriveplayer.to embed2.php builds playlist url via CryptoJS(AES) + JS packer.
        if (embedUrl.contains("/embed2.php", ignoreCase = true)) {
            return tryExtractGdriveplayerEmbed2(embedUrl, ref, qualityHint, callback)
        }

        val html = runCatching { app.get(embedUrl, referer = ref).text }.getOrNull() ?: return false
        val playlistRaw = Regex(
            """(?i)(https?://[^\s"'<>]+/hlsplaylist\.php\?[^\s"'<>]+|/hlsplaylist\.php\?[^\s"'<>]+)"""
        ).find(html)?.groupValues?.getOrNull(1) ?: return false

        val playlistUrl = if (playlistRaw.startsWith("http")) playlistRaw else "$mainUrl$playlistRaw"
        val master = runCatching { app.get(playlistUrl, referer = ref).text }.getOrNull() ?: return false
        if (!master.trimStart().startsWith("#EXTM3U")) return false

        val variants = parseGdriveVariants(master, playlistUrl)
        if (variants.isEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = "Gdriveplayer",
                    name = "Gdriveplayer",
                    url = playlistUrl
                ) {
                    this.referer = ref
                    this.type = ExtractorLinkType.M3U8
                    this.quality = qualityHint ?: Qualities.Unknown.value
                    this.headers = mapOf("Range" to "bytes=0-")
                }
            )
            return true
        }

        variants.distinctBy { it.second }.forEach { (vUrl, vQ) ->
            callback.invoke(
                newExtractorLink(
                    source = "Gdriveplayer",
                    name = "Gdriveplayer ${vQ}p",
                    url = vUrl
                ) {
                    this.referer = ref
                    this.type = ExtractorLinkType.M3U8
                    this.quality = vQ
                    this.headers = mapOf("Range" to "bytes=0-")
                }
            )
        }
        return true
    }

    private suspend fun tryExtractGdriveplayerEmbed2(
        embedUrl: String,
        upstreamReferer: String,
        qualityHint: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = runCatching { app.get(embedUrl, referer = upstreamReferer) }.getOrNull() ?: return false
        val html = runCatching { resp.text }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

        val ids = Regex("""\bvar\s+ids\s*=\s*["']([0-9a-f]{16,64})["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        val cookieHeader = ids?.let { "newaccess=$it" }

        val pass = extractPassFromFromCharCode(html) ?: return false
        val dataJson = extractCryptoJsDataJsonFromPackedScripts(html) ?: return false
        val payload = tryParseJson<CryptoJsAesJson>(dataJson) ?: return false
        val decrypted = cryptoJsPasswordDecrypt(payload, pass) ?: return false

        // The decrypted blob typically contains another packed eval(...) that holds the player setup.
        val decryptedUnpacked = runCatching { getAndUnpack(decrypted) }.getOrNull() ?: decrypted
        val playlistRaw = Regex(
            """(?i)(https?://[^\s"'<>]+/hlsplaylist\.php\?[^\s"'<>]+|/hlsplaylist\.php\?[^\s"'<>]+|hlsplaylist\.php\?[^\s"'<>]+)"""
        ).find(decryptedUnpacked)?.groupValues?.getOrNull(1) ?: return false

        val origin = "https://gdriveplayer.to"
        val playlistUrl = when {
            playlistRaw.startsWith("http") -> playlistRaw
            playlistRaw.startsWith("/") -> "$origin$playlistRaw"
            else -> "$origin/$playlistRaw"
        }

        val master = runCatching {
            app.get(
                playlistUrl,
                referer = embedUrl,
                headers = cookieHeader?.let { mapOf("Cookie" to it) } ?: emptyMap(),
            ).text
        }.getOrNull() ?: return false

        if (!master.trimStart().startsWith("#EXTM3U")) return false

        val variants = parseGdriveVariants(master, playlistUrl)
        if (variants.isEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = "Gdriveplayer",
                    name = "Gdriveplayer",
                    url = playlistUrl
                ) {
                    this.referer = origin
                    this.type = ExtractorLinkType.M3U8
                    this.quality = qualityHint ?: Qualities.Unknown.value
                    this.headers = buildMap {
                        put("Range", "bytes=0-")
                        cookieHeader?.let { put("Cookie", it) }
                    }
                }
            )
            return true
        }

        variants.distinctBy { it.second }.forEach { (vUrl, vQ) ->
            callback.invoke(
                newExtractorLink(
                    source = "Gdriveplayer",
                    name = "Gdriveplayer ${vQ}p",
                    url = vUrl
                ) {
                    this.referer = origin
                    this.type = ExtractorLinkType.M3U8
                    this.quality = vQ
                    this.headers = buildMap {
                        put("Range", "bytes=0-")
                        cookieHeader?.let { put("Cookie", it) }
                    }
                }
            )
        }
        return true
    }

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
        // For /embed/ pages, downstream iframe loads typically use origin referer (kotakajaib.me),
        // so keep referer stable to maximize compatibility.
        val pageReferer = referer ?: "$mainUrl/"

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
        suspend fun fetchDocWith(ref: String) = runCatching {
            app.get(
                pageUrl,
                referer = ref,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    "Accept" to "*/*"
                )
            ).document
        }.getOrNull()

        var document = fetchDocWith(referer) ?: return
        val hasServerButton = document.selectFirst("button.server-item[data-frame], button.server-item[data-url], button.server-item[data-src]") != null
        if (!hasServerButton) {
            // kotakajaib embed checks referrer host; retry with known whitelisted referrer.
            document = fetchDocWith("https://v3.pusatfilm21info.com/") ?: document
        }
        val visited = linkedSetOf<String>()
        // Many embeds (including gdriveplayer.to) validate referer. Use the kotakajaib embed page as referer.
        val downstreamReferer = pageUrl

        fun normalizeServerTarget(dataFrameOrUrl: String?): String? {
            val raw = dataFrameOrUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null

            fun looksLikeTarget(value: String): Boolean {
                val v = value.trim()
                return v.startsWith("http", ignoreCase = true) || v.startsWith("//") || v.startsWith("/")
            }

            val decoded = runCatching { base64Decode(raw).trim() }.getOrNull()
            val candidate = when {
                !decoded.isNullOrBlank() && looksLikeTarget(decoded) -> decoded
                looksLikeTarget(raw) -> raw
                else -> null
            } ?: return null

            // Some uplayer links need the encoded r= referer parameter.
            if (candidate.contains("uplayer.xyz", ignoreCase = true) && !candidate.contains("r=")) {
                val encodedRef = java.util.Base64.getEncoder()
                    .encodeToString("https://v3.pusatfilm21info.com/".toByteArray(StandardCharsets.UTF_8))
                val separator = if (candidate.contains("?")) "&" else "?"
                return "$candidate${separator}r=$encodedRef"
            }
            return candidate
        }

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

            emitOrExtract(normalized, downstreamReferer, quality, subtitleCallback, callback)
        }

        // /embed/{id} pages use buttons with base64 payloads (multi-server selector)
        document.select("button.server-item[data-frame], button.server-item[data-url], button.server-item[data-src]").forEach { btn ->
            val dataFrame = btn.attr("data-frame").takeIf { it.isNotBlank() }
            val raw =
                dataFrame
                    ?: btn.attr("data-url").takeIf { it.isNotBlank() }
                    ?: btn.attr("data-src").takeIf { it.isNotBlank() }
            parseTarget(normalizeServerTarget(raw))
        }

        // Legacy structure support.
        document.select("ul#dropdown-server li a[data-frame], a.server-item[data-frame]").forEach { a ->
            parseTarget(normalizeServerTarget(a.attr("data-frame")))
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
        fun isOuoHost(host: String?): Boolean {
            val h = host?.lowercase().orEmpty()
            return h.contains("ouo.io") || h.contains("ouo.press") || h.contains("ouo-")
        }
        fun resolveAgainst(base: String, target: String): String? = runCatching {
            when {
                target.startsWith("http://", true) || target.startsWith("https://", true) -> target
                target.startsWith("//") -> "https:$target"
                else -> java.net.URI(base).resolve(target).toString()
            }
        }.getOrNull()

        val firstHost = runCatching { java.net.URI(url).host }.getOrNull()
        if (!isOuoHost(firstHost)) return null

        var current = url
        repeat(4) {
            val resp = runCatching {
                app.get(
                    current,
                    referer = referer,
                    allowRedirects = false,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
            }.getOrNull() ?: return null

            val location = resp.headers["Location"] ?: resp.headers["location"]
            if (!location.isNullOrBlank()) {
                val next = resolveAgainst(current, location) ?: return null
                val nextHost = runCatching { java.net.URI(next).host }.getOrNull()
                if (!isOuoHost(nextHost)) return next
                current = next
                return@repeat
            }

            val html = runCatching { resp.text }.getOrNull()?.trim().orEmpty()
            if (html.isBlank()) return null

            // If it looks like an interaction/challenge page, don't proceed.
            if (html.contains("I'M A HUMAN", ignoreCase = true) ||
                html.contains("I am human", ignoreCase = true) ||
                html.contains("captcha", ignoreCase = true) ||
                html.contains("g-recaptcha", ignoreCase = true) ||
                html.contains("cf-turnstile", ignoreCase = true)
            ) return null

            val nextCandidate = Regex(
                """http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["'][^"']*url\s*=\s*([^"'>\s]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""location\.(?:href|replace)\(['"]([^'"]+)['"]\)""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""<a[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)

            val next = nextCandidate?.let { resolveAgainst(current, it) } ?: return null
            val nextHost = runCatching { java.net.URI(next).host }.getOrNull()
            if (!isOuoHost(nextHost)) return next
            current = next
        }
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

        // gdriveplayer.to is often served in a way that breaks the upstream AES-based extractor.
        // Handle the observed embed2.php -> hlsplaylist.php -> hlsnew2.php flow directly.
        if (runCatching { java.net.URI(fixed).host?.lowercase() }.getOrNull() == "gdriveplayer.to") {
            if (tryExtractGdriveplayer(fixed, referer, quality, callback)) return
        }

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

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private fun originOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return@runCatching null
        val host = u.host ?: return@runCatching null
        val port = if (u.port != -1) ":${u.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    private fun resolve(url: String, base: String): String {
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            else -> runCatching { java.net.URI(base).resolve(url).toString() }.getOrElse { url }
        }
    }

    private fun parseUrlPlay(html: String): String? {
        val raw = Regex("""var\s+urlPlay\s*=\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\x3d", "=")
            .replace("&amp;", "&")
            .trim()
    }

    private fun parseMasterVariants(masterUrl: String, text: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (next.isBlank() || next.startsWith("#")) continue

            val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: getQualityFromName(next).takeIf { it != Qualities.Unknown.value }
                ?: Qualities.Unknown.value

            out += resolve(next, masterUrl) to height
        }
        return out
    }

    private fun parseFirstMediaEntry(playlistUrl: String, text: String): String? {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: return null
        return resolve(line, playlistUrl)
    }

    private fun isLikelyMediaChunk(bytes: ByteArray, contentType: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("image/") || ct.contains("text/html")) return false

        if (bytes.size >= 8) {
            // PNG signature
            if (bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) return false

            // fMP4 signature (....ftyp)
            if (bytes[4] == 0x66.toByte() &&
                bytes[5] == 0x74.toByte() &&
                bytes[6] == 0x79.toByte() &&
                bytes[7] == 0x70.toByte()
            ) return true
        }

        // MPEG-TS sync byte at start or at 188-byte boundary.
        if (bytes.isNotEmpty() && bytes[0] == 0x47.toByte()) return true
        if (bytes.size > 188 && bytes[188] == 0x47.toByte()) return true

        return ct.contains("video") || ct.contains("application/octet-stream")
    }

    private suspend fun isPlayableVariant(
        variantUrl: String,
        headers: Map<String, String>,
        referer: String
    ): Boolean {
        val playlistText = runCatching {
            app.get(variantUrl, headers = headers, referer = referer).text
        }.getOrNull() ?: return false
        if (!playlistText.contains("#EXTM3U", ignoreCase = true)) return false

        val segmentUrl = parseFirstMediaEntry(variantUrl, playlistText) ?: return false
        val segResp = runCatching {
            app.get(segmentUrl, headers = headers, referer = referer)
        }.getOrNull() ?: return false

        val contentType = segResp.headers["Content-Type"] ?: segResp.headers["content-type"]
        val body = runCatching { segResp.body.bytes() }.getOrNull() ?: return false
        return isLikelyMediaChunk(body, contentType)
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val entryRef = referer ?: "$mainUrl/"
        val response = runCatching {
            app.get(
                url,
                referer = entryRef,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return null

        val pageUrl = response.url
        val origin = originOf(pageUrl) ?: mainUrl

        val m3u8Raw = parseUrlPlay(response.text) ?: return null
        val masterUrl = resolve(m3u8Raw, pageUrl)

        val playlistHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Origin" to origin,
            "Referer" to pageUrl
        )

        val masterText = runCatching {
            app.get(masterUrl, referer = pageUrl, headers = playlistHeaders).text
        }.getOrNull() ?: return null

        if (!masterText.contains("#EXTM3U", ignoreCase = true)) return null

        val variants = parseMasterVariants(masterUrl, masterText).distinctBy { it.first }
        if (variants.isEmpty()) {
            if (!isPlayableVariant(masterUrl, playlistHeaders, pageUrl)) return null
            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = pageUrl
                    this.headers = playlistHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val playable = variants.filter { (variantUrl, _) ->
            isPlayableVariant(variantUrl, playlistHeaders, pageUrl)
        }
        if (playable.isEmpty()) return null

        return playable.map { (variantUrl, quality) ->
            newExtractorLink(
                source = name,
                name = if (quality == Qualities.Unknown.value) name else "$name ${quality}p",
                url = variantUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = pageUrl
                this.headers = playlistHeaders
                this.quality = quality
            }
        }
    }
}

class PlayhydraxExtractor : ExtractorApi() {
    override val name = "Hydrax"
    override val mainUrl = "https://playhydrax.com"
    override val requiresReferer = true

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        val chars = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { b ->
            val v = b.toInt() and 0xff
            out.append(chars[v ushr 4])
            out.append(chars[v and 0x0f])
        }
        return out.toString()
    }

    private fun normalizeUrl(raw: String): String {
        val fixed = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "$mainUrl/${raw.trimStart('/')}"
        }
        val vId = Regex("""/(?:v|f|d|file|download)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(fixed)?.groupValues?.getOrNull(1)
        return if (!vId.isNullOrBlank()) "$mainUrl/?v=$vId" else fixed
    }

    private fun isLikelyPlayable(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("m3u8") || Regex("""\.(mp4|mkv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[4] == 0x66.toByte() &&
            bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() &&
            bytes[7] == 0x70.toByte()
    }

    private suspend fun detectPlayableType(
        url: String,
        referer: String
    ): ExtractorLinkType? {
        if (url.contains("m3u8", true)) return ExtractorLinkType.M3U8
        if (Regex("""\.(mp4|mkv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return null

        val probe = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf("Range" to "bytes=0-4095")
            )
        }.getOrNull() ?: return null

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        val headBytes = runCatching { probe.body.bytes().take(4096).toByteArray() }.getOrNull() ?: return null
        val headText = runCatching { String(headBytes, Charsets.UTF_8) }.getOrNull().orEmpty()

        if (contentType.contains("application/vnd.apple.mpegurl") || headText.contains("#EXTM3U")) {
            return ExtractorLinkType.M3U8
        }
        if (contentType.contains("video/mp4") || looksLikeMp4(headBytes)) {
            return null
        }
        return null
    }

    private fun parseQuality(label: String?, resId: Int): Int {
        val fromLabel = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(label.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (fromLabel != null) return fromLabel
        return when (resId) {
            1 -> 240
            2 -> 360
            3 -> 480
            4 -> 720
            5 -> 1080
            6 -> 1440
            7 -> 2160
            else -> Qualities.Unknown.value
        }
    }

    private fun extractCandidate(source: JSONObject): String? {
        val direct = listOf("file", "src", "link", "playlist")
            .firstNotNullOfOrNull { key ->
                source.optString(key).trim().takeIf { it.isNotBlank() }
            }
        if (!direct.isNullOrBlank()) return direct

        val hostUrl = source.optString("url").trim()
        val path = source.optString("path").trim()
        if (hostUrl.isNotBlank() && path.isNotBlank()) {
            return "${hostUrl.trimEnd('/')}/${path.trimStart('/')}"
        }
        return null
    }

    private fun decryptMedia(datas: JSONObject): JSONObject? {
        val slug = datas.optString("slug").trim()
        val userId = datas.opt("user_id")?.toString()?.trim().orEmpty()
        val md5Id = datas.opt("md5_id")?.toString()?.trim().orEmpty()
        val encryptedMedia = datas.optString("media")
        if (slug.isBlank() || userId.isBlank() || md5Id.isBlank() || encryptedMedia.isBlank()) return null

        val keySeed = "$userId:$slug:$md5Id"
        val key = md5Hex(keySeed).toByteArray(StandardCharsets.UTF_8)
        if (key.size < 16) return null
        val iv = key.copyOfRange(0, 16)

        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = encryptedMedia.toByteArray(Charsets.ISO_8859_1)
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null

        return runCatching { JSONObject(plain) }.getOrNull()
    }

    private suspend fun emitSection(
        sectionName: String,
        section: JSONObject?,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (section == null) return false
        val sources = section.optJSONArray("sources") ?: return false
        var emitted = false
        val emittedUrls = linkedSetOf<String>()

        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            if (!item.optBoolean("status", true)) continue

            val candidate = extractCandidate(item) ?: continue
            val fixed = when {
                candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
                candidate.startsWith("//") -> "https:$candidate"
                else -> continue
            }
            if (!emittedUrls.add(fixed)) continue
            val hasKnownPattern = isLikelyPlayable(fixed)
            val detectedType = detectPlayableType(fixed, pageUrl)
            if (!hasKnownPattern && detectedType == null) continue

            val quality = parseQuality(item.optString("label"), item.optInt("res_id", -1))
            val linkName = if (quality == Qualities.Unknown.value) name else "$name ${quality}p"
            val headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to pageUrl
            )

            if (detectedType == ExtractorLinkType.M3U8 || fixed.contains("m3u8", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = "$name-$sectionName",
                        name = linkName,
                        url = fixed,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.headers = headers
                        this.quality = quality
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "$name-$sectionName",
                        name = linkName,
                        url = fixed
                    ) {
                        this.referer = pageUrl
                        this.headers = headers
                        this.quality = quality
                    }
                )
            }
            emitted = true
        }
        return emitted
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeUrl(url)
        val resp = runCatching { app.get(pageUrl, referer = referer ?: "$mainUrl/") }.getOrNull() ?: return
        val html = resp.text
        val datasB64 = Regex("""\bconst\s+datas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return

        val datas = runCatching {
            val jsonText = String(java.util.Base64.getDecoder().decode(datasB64), Charsets.ISO_8859_1)
            JSONObject(jsonText)
        }.getOrNull() ?: return

        val media = decryptMedia(datas) ?: return
        var emitted = false
        emitted = emitSection("HLS", media.optJSONObject("hls"), pageUrl, callback) || emitted
        emitted = emitSection("MP4", media.optJSONObject("mp4"), pageUrl, callback) || emitted

        if (!emitted) {
            // Fallback in case direct m3u8 appears in decrypted blob in future variants.
            val m3u8Links = Regex("""https?://[^\s"'<>]+m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .findAll(media.toString())
                .map { it.value }
                .distinct()
                .toList()

            for (m3u8 in m3u8Links) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageUrl
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to pageUrl
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}

/**
 * gdriveplayer.to has multiple implementations floating around in Cloudstream.
 * The upstream extractor in some versions expects packed+AES JS, but the current
 * site flow we see in Pusatfilm/Kotakajaib uses:
 * - embed2.php -> hlsplaylist.php -> hlsnew2.php (m3u8)
 *
 * This extractor implements that flow directly.
 */
class Gdriveplayerto : ExtractorApi() {
    override val name = "Gdriveplayer"
    override val mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    private data class Variant(val url: String, val quality: Int)

    private fun baseOrigin(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val scheme = u.scheme ?: return@runCatching null
        val host = u.host ?: return@runCatching null
        "$scheme://$host/"
    }.getOrNull()

    private fun parseVariants(master: String, baseUrl: String): List<Variant> {
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val next = lines.getOrNull(i + 1) ?: continue

            val qFromName = Regex("""NAME\s*=\s*"(\d{3,4})p"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qFromRes = Regex("""RESOLUTION\s*=\s*\d+\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qFromType = Regex("""[?&]type=(\d{3,4})""", RegexOption.IGNORE_CASE)
                .find(next)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val quality = qFromName ?: qFromRes ?: qFromType ?: Qualities.Unknown.value
            val abs = runCatching { java.net.URI(baseUrl).resolve(next).toString() }.getOrNull() ?: continue
            out.add(Variant(abs, quality))
        }
        return out
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // gdriveplayer.to endpoints appear to accept kotakajaib.me as referer; use origin if possible.
        val ref = baseOrigin(referer ?: "") ?: "https://kotakajaib.me/"

        val resp = runCatching { app.get(url, referer = ref) }.getOrNull() ?: return
        val html = runCatching { resp.text }.getOrNull() ?: return

        // Prefer embed2.php decrypt flow if present.
        if (url.contains("/embed2.php", ignoreCase = true)) {
            val ids = Regex("""\bvar\s+ids\s*=\s*["']([0-9a-f]{16,64})["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            val cookieHeader = ids?.let { "newaccess=$it" }

            val pass = extractPassFromFromCharCode(html) ?: return
            val dataJson = extractCryptoJsDataJsonFromPackedScripts(html) ?: return
            val payload = tryParseJson<CryptoJsAesJson>(dataJson) ?: return
            val decrypted = cryptoJsPasswordDecrypt(payload, pass) ?: return
            val decryptedUnpacked = runCatching { getAndUnpack(decrypted) }.getOrNull() ?: decrypted

            val playlistRaw = Regex(
                """(?i)(https?://[^\s"'<>]+/hlsplaylist\.php\?[^\s"'<>]+|/hlsplaylist\.php\?[^\s"'<>]+|hlsplaylist\.php\?[^\s"'<>]+)"""
            ).find(decryptedUnpacked)?.groupValues?.getOrNull(1) ?: return

            val origin = "https://gdriveplayer.to"
            val playlistUrl = when {
                playlistRaw.startsWith("http") -> playlistRaw
                playlistRaw.startsWith("/") -> "$origin$playlistRaw"
                else -> "$origin/$playlistRaw"
            }

            val master = runCatching {
                app.get(
                    playlistUrl,
                    referer = url,
                    headers = cookieHeader?.let { mapOf("Cookie" to it) } ?: emptyMap(),
                ).text
            }.getOrNull() ?: return

            if (!master.trimStart().startsWith("#EXTM3U")) return
            val variants = parseVariants(master, playlistUrl)
            if (variants.isEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = playlistUrl
                    ) {
                        this.referer = origin
                        this.type = ExtractorLinkType.M3U8
                        this.quality = Qualities.Unknown.value
                        this.headers = buildMap {
                            put("Range", "bytes=0-")
                            cookieHeader?.let { put("Cookie", it) }
                        }
                    }
                )
                return
            }
            variants.distinctBy { it.quality }.forEach { v ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "${name} ${v.quality}p",
                        url = v.url
                    ) {
                        this.referer = origin
                        this.type = ExtractorLinkType.M3U8
                        this.quality = v.quality
                        this.headers = buildMap {
                            put("Range", "bytes=0-")
                            cookieHeader?.let { put("Cookie", it) }
                        }
                    }
                )
            }
            return
        }

        val playlistRaw = Regex(
            """(?i)(https?://[^\s"'<>]+/hlsplaylist\.php\?[^\s"'<>]+|/hlsplaylist\.php\?[^\s"'<>]+)"""
        ).find(html)?.groupValues?.getOrNull(1)

        val playlistUrl = when {
            playlistRaw.isNullOrBlank() -> null
            playlistRaw.startsWith("http") -> playlistRaw
            else -> "$mainUrl${playlistRaw}"
        } ?: return

        val master = runCatching { app.get(playlistUrl, referer = ref).text }.getOrNull() ?: return
        if (!master.trimStart().startsWith("#EXTM3U")) return

        val variants = parseVariants(master, playlistUrl)
        if (variants.isEmpty()) {
            // Sometimes hlsplaylist.php may already be a single-variant m3u8
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = playlistUrl
                ) {
                    this.referer = ref
                    this.type = ExtractorLinkType.M3U8
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Range" to "bytes=0-")
                }
            )
            return
        }

        variants.distinctBy { it.quality }.forEach { v ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${name} ${v.quality}p",
                    url = v.url
                ) {
                    this.referer = ref
                    this.type = ExtractorLinkType.M3U8
                    this.quality = v.quality
                    this.headers = mapOf("Range" to "bytes=0-")
                }
            )
        }
    }
}
