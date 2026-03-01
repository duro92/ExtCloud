package com.shortmax

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShortmaxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shortmax())
    }
}
