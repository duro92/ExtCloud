package com.freereels

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreeReelsPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(FreeReels())
    }
}
