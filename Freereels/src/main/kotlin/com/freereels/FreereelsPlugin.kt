package com.freereels

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreereelsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Freereels())
    }
}
