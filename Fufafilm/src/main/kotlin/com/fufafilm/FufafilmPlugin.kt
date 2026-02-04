package com.fufafilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FufafilmPlugin : Plugin() {
    override fun load(context: Context) {
        Fufafilm.context = context
        registerMainAPI(Fufafilm())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Gdriveplayerto())
        registerExtractorAPI(Playerngefilm21())
        registerExtractorAPI(P2pplay())
        registerExtractorAPI(Xshotcok())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Vidhidepre())
        registerExtractorAPI(Fufaupns())
        registerExtractorAPI(Fufastrp2p())
        registerExtractorAPI(Minochinos())
    }
}

