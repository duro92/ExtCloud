package com.dramabox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaboxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramabox())
    }
}
