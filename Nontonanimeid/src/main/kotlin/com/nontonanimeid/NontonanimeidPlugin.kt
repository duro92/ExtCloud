package com.nontonanimeid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonanimeidPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nontonanimeid())
         registerExtractorAPI(Rpmvip())
    }
}
