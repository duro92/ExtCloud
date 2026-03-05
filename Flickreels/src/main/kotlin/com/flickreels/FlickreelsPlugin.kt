package com.flickreels

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FlickreelsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Flickreels())
    }
}
