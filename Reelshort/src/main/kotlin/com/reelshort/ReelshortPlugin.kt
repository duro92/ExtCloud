package com.reelshort

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelshortPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Reelshort())
    }
}
