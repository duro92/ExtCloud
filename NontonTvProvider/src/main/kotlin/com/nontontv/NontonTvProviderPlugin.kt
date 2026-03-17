package com.nontontv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonTvProviderPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(NontonTvProvider())
    }
}
