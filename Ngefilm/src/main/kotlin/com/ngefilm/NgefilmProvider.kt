package com.ngefilm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NgefilmProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Ngefilm())
    }
}

