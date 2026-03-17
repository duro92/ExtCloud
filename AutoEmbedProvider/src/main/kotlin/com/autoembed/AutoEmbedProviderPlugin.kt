package com.autoembed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AutoEmbedProviderPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(AutoEmbedProvider())
    }
}
