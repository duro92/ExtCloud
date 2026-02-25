package com.gojodesu

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
import com.lagradost.cloudstream3.USER_AGENT
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        val document = runCatching { app.get(pageUrl, referer = referer).document }.getOrNull() ?: return
        val visited = linkedSetOf<String>()
        // Many embeds (including gdriveplayer.to) validate referer. Use the kotakajaib embed page as referer.
        val downstreamReferer = pageUrl

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

    private val UA =
        try { USER_AGENT } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

    private fun absoluteUrl(base: String, value: String): String {
        val raw = value.trim()
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> runCatching { java.net.URI(base).resolve(raw).toString() }.getOrDefault(raw)
        }
    }

    private fun parseVariants(master: String, masterUrl: String): List<Pair<String, Int>> {
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val out = ArrayList<Pair<String, Int>>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (next.startsWith("#")) continue

            val height = Regex("""RESOLUTION\s*=\s*\d+\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val q = height ?: Qualities.Unknown.value
            out += absoluteUrl(masterUrl, next) to q
        }
        return out
    }

    private fun findIds(text: String): Pair<String, String>? {
   
        val vid = Regex("""videoID["']?\s*[:=]\s*["']?([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val uid = Regex("""userID["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        return if (!vid.isNullOrBlank() && !uid.isNullOrBlank()) vid to uid else null
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageRef = referer ?: "$mainUrl/"

        
        val headers = mapOf(
            "Referer" to pageRef,
            "User-Agent" to UA,
            "Accept" to "*/*"
        )

        
        val page = app.get(url, referer = pageRef, headers = headers)
        val pageText = page.text

        
        val masterRaw = Regex("""\bvar\s+urlPlay\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(pageText)?.groupValues?.getOrNull(1)
            ?: return null

        val masterUrl = absoluteUrl(page.url, masterRaw)

        findIds(pageText)?.let { (vid, uid) ->
            runCatching {
                app.get("https://ver03.sptvp.com/watch?videoID=$vid&userID=$uid", referer = pageRef, headers = headers)
            }
            runCatching {
                app.get("https://ver02.sptvp.com/watch?videoID=$vid&userID=$uid", referer = pageRef, headers = headers)
            }
        }

     
        val masterText = runCatching {
            app.get(masterUrl, referer = pageRef, headers = headers).text
        }.getOrNull().orEmpty()

        val variants = if (masterText.trimStart().startsWith("#EXTM3U")) {
            parseVariants(masterText, masterUrl)
        } else emptyList()

        if (variants.isNotEmpty()) {
            return variants
                .distinctBy { it.first }
                .sortedByDescending { it.second }
                .map { (variantUrl, q) ->
                    newExtractorLink(
                        source = name,
                        name = name, 
                        url = variantUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = pageRef
                        this.headers = headers
                        this.quality = q
                    }
                }
        }

     
        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = pageRef
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        )
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
