package com.melongmovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MelongmoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Melongmovie())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Veev())
        registerExtractorAPI(Veev2())
        registerExtractorAPI(Veev3())
    }
}
