package com.pmsm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PmsmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pmsm())
        registerExtractorAPI(DhtprePmsm())
        registerExtractorAPI(NetuPmsm())
        registerExtractorAPI(Playerxupns())
        registerExtractorAPI(Playerxp2p())
        registerExtractorAPI(Playerxseek())
        registerExtractorAPI(Playerxrpms())
        registerExtractorAPI(Player4me())
        registerExtractorAPI(Ezplayer())
        registerExtractorAPI(YandexcdnPmsm())
    }
}
