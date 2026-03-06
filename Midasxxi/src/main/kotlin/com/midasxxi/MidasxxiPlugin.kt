
package com.midasxxi

import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin



@CloudstreamPlugin
class MidasxxiPlugin: Plugin() {
    override fun load(context: Context) {
        Midasxxi.context = context
        registerMainAPI(Midasxxi())
        registerExtractorAPI(Playcinematic())
    }
}
