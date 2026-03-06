package com.kisskh

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KisskhProviderPlugin : Plugin() {
    override fun load(context: Context) {
        KisskhProvider.context = context
        registerMainAPI(KisskhProvider())
    }
}