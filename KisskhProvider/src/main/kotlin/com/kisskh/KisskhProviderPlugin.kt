package com.kisskh

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KisskhProviderPlugin: BasePlugin() {
    override fun load() {
        KisskhProvider.context = context
        registerMainAPI(KisskhProvider())
    }
}