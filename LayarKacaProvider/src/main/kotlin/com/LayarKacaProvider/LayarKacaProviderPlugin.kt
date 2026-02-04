package com.layarKacaProvider

import com.lagradost.cloudstream3.extractors.VidHidePro6
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LayarKacaProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        LayarKacaProvider.context = context
        registerMainAPI(LayarKacaProvider())
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(Furher())
        registerExtractorAPI(Hownetwork())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(Furher2())
        registerExtractorAPI(Co4nxtrl())
        registerExtractorAPI(Cloudhownetwork())
        registerExtractorAPI(F16px())
    }
}