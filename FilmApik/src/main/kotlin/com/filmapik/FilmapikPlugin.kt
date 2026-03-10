
package com.filmapik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmapikPlugin : Plugin() {
    override fun load(context: Context) {
        Filmapik.context = context
        registerMainAPI(Filmapik())
        registerExtractorAPI(BuzzServer())
    }
}
