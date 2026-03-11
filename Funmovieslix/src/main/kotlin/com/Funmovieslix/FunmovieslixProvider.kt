package com.Funmovieslix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.FileMoonIn
import com.lagradost.cloudstream3.extractors.FilemoonV2

@CloudstreamPlugin
class FunmovieslixProvider : Plugin() {
    override fun load(context: Context) {
        Funmovieslix.context = context
        registerMainAPI(Funmovieslix())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(FilemoonV2())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(Vidhideplus())
        registerExtractorAPI(VideyV2())
        registerExtractorAPI(F75s())
    }
}
