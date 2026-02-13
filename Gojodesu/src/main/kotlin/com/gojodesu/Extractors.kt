package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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
        val document = app.get(url, referer = referer).document

        // Embed servers (data-frame)
        val links = document.select("ul#dropdown-server li a")
        for (a in links) {
            loadExtractor(
                base64Decode(a.attr("data-frame")),
                "$mainUrl/",
                subtitleCallback,
                callback
            )
        }

        // Download buttons on Kotakajaib page
        val downloadLinks = document.select(
            "a:contains(Download), a:contains(download), span.textdownload"
        ).mapNotNull { el ->
            if (el.tagName() == "span") el.parent()?.attr("href") else el.attr("href")
        }.mapNotNull { it.takeIf { link -> link.isNotBlank() } }

        downloadLinks.forEach { link ->
            val fixed = link
                .replace("&amp;", "&")
                .takeIf { it.isNotBlank() } ?: return@forEach

            // Jangan proses shortlink ouo
            if (fixed.contains("ouo.io") || fixed.contains("ouo.press")) return@forEach

            // Pixeldrain direct (u/ID atau file/ID)
            if (fixed.contains("pixeldrain.com/")) {
                val fileId = when {
                    fixed.contains("/u/") -> fixed.substringAfter("/u/").substringBefore("?").substringBefore("/")
                    fixed.contains("/file/") -> fixed.substringAfter("/file/").substringBefore("?").substringBefore("/")
                    else -> ""
                }
                if (fileId.isNotBlank()) {
                    val apiUrl = "https://pixeldrain.com/api/file/$fileId"
                    loadExtractor(apiUrl, "https://pixeldrain.com/", subtitleCallback, callback)
                    return@forEach
                }
            }

            loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)

            // Jika link mengarah ke endpoint API Kotakajaib, coba ambil url langsung
            if (fixed.contains("/api/file/") && fixed.contains("/download")) {
                val apiResp = app.get(fixed, referer = "$mainUrl/").text
                Regex("https?://[^\"'\\s]+").findAll(apiResp).forEach { m ->
                    val u = m.value
                    if (!u.contains("ouo.io") && !u.contains("ouo.press")) {
                        loadExtractor(u, "$mainUrl/", subtitleCallback, callback)
                    }
                }

                val parsed = runCatching { tryParseJson<Map<String, Any>>(apiResp) }.getOrNull()
                parsed?.values?.forEach { v ->
                    val s = v?.toString() ?: return@forEach
                    if (s.startsWith("http") && !s.contains("ouo.io") && !s.contains("ouo.press")) {
                        loadExtractor(s, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }
    }
}

class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"
}
