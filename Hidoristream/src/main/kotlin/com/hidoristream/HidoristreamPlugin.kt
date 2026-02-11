package com.hidoristream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class HidoristreamPlugin : Plugin() {
    override fun load(context: Context) {
        Hidoristream.context = context
        registerMainAPI(Hidoristream())
    }
}
