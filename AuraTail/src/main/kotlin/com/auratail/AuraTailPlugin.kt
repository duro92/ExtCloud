package com.auratail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AuraTailPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AuraTail())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dm21upns())
        registerExtractorAPI(Dm21())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Dm21embed())
        registerExtractorAPI(Veev())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Serhmeplayer())
    }
}
