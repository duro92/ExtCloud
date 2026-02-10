package com.anoboy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class AnoboyPlugin : Plugin() {
    override fun load(context: Context) {
        Anoboy.context = context
        registerMainAPI(Anoboy())
    }
}
