package com.melolo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MeloloPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(Melolo())
    }
}
