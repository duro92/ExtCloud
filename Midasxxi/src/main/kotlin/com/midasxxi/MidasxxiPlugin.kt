package com.midasxxi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MidasxxiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Midasxxi())
        registerExtractorAPI(PlayCinematic())
    }
}
