package com.kuramanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking

object KuramanimeExtractors {
    suspend fun loadKnownExtractor(
        url: String,
        serverName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                val finalName = if (link.name.equals(serverName, true)) {
                    link.name
                } else {
                    "$serverName - ${link.name}"
                }

                runBlocking {
                    callback.invoke(
                        newExtractorLink(
                            source = link.name,
                            name = finalName,
                            url = link.url,
                            type = link.type,
                        ) {
                            this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }
    }
}
