package com.oppadrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class OppadramaPlugin : Plugin() {
    override fun load(context: Context) {
        Oppadrama.context = context
        registerMainAPI(Oppadrama())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(BuzzServer())
    }
}
