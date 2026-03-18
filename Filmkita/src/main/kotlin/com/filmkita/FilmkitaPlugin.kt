package com.filmkita

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmkitaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Filmkita())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Winvids())
        registerExtractorAPI(LayarwibuHls())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Vidshare())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Minochinos())
    }
}
