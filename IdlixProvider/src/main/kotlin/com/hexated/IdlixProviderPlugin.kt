
package com.hexated

import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin



@CloudstreamPlugin
class IdlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        IdlixProvider.context = context
        registerMainAPI(IdlixProvider())  
        registerExtractorAPI(Jeniusplay())
    }
}