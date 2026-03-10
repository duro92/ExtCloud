package com.dramabox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaboxPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(Dramabox())
    }
}
