
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NontonAnimeIDProviderPlugin: Plugin() {
    override fun load(context: Context) {
        NontonAnimeIDProvider.context = context
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(NontonAnimeIDProvider())
        registerExtractorAPI(Nontonanimeid())
        registerExtractorAPI(EmbedKotakAnimeid())
        registerExtractorAPI(KotakAnimeidCom())
        registerExtractorAPI(KotakAnimeidLink())
        registerExtractorAPI(KotakAnimeidS1())
        registerExtractorAPI(KotakAnimeidS2())
        registerExtractorAPI(Gdplayer())
        registerExtractorAPI(Kotaksb())
        registerExtractorAPI(Gdriveplayerto())
        registerExtractorAPI(Vidhidepre())
        registerExtractorAPI(Rpmvip())
    }
}
