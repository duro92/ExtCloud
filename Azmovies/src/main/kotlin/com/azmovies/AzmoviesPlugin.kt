package com.azmovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AzmoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Azmovies())
        registerMainAPI(Noxx())
        registerExtractorAPI(ByseSayeveum())
        registerExtractorAPI(MyvidplayAz())
        registerExtractorAPI(HqqAz())
        registerExtractorAPI(VidsrcXyzAz())
    }
}
