package com.oppadrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro

class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}
