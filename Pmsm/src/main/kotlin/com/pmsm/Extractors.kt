package com.pmsm

import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.VidStack

class DhtprePmsm : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class NetuPmsm : VidhideExtractor() {
    override var mainUrl = "https://netu.msmbot.club"
}

class Playerxupns : VidStack() {
    override var name = "Playerxupns"
    override var mainUrl = "https://playerx.upns.live"
    override var requiresReferer = true
}

class Playerxp2p : VidStack() {
    override var name = "Playerxp2p"
    override var mainUrl = "https://playerx.p2pstream.online"
    override var requiresReferer = true
}

class Playerxseek : VidStack() {
    override var name = "Playerxseek"
    override var mainUrl = "https://playerx.seekplays.online"
    override var requiresReferer = true
}

class Playerxrpms : VidStack() {
    override var name = "Playerxrpms"
    override var mainUrl = "https://playerx.rpmstream.online"
    override var requiresReferer = true
}

class Player4me : VidStack() {
    override var name = "Player4me"
    override var mainUrl = "https://playerx.player4me.online"
    override var requiresReferer = true
}

class Ezplayer : VidStack() {
    override var name = "Ezplayer"
    override var mainUrl = "https://playerx.ezplayer.stream"
    override var requiresReferer = true
}

class YandexcdnPmsm : VidhideExtractor() {
    override var mainUrl = "https://yandexcdn.com"
}
