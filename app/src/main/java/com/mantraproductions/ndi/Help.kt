package com.mantraproductions.ndi

/**
 * v155 (Marko, 26.10.2026: "long press of any of the action buttons after 2 seconds. Open the help menu explaining
 * what the button does"): every key's help, by the name the key goes by (its label, MARK0-2, a glyph's name).
 */
object Help {
    fun forKey(name: String, power: PowerMonitor.Reading? = null): Pair<String, String> = when {
        name == "L" -> "L · LENSES" to "Opens the drawer of this phone's lenses (wide, main, tele, selfie). A tap on one switches to it; the stream goes on. The lens is remembered."
        name.matches(Regex("L\\d.*")) -> "LENS ${name.drop(1).take(1)}" to "Switches to this lens. Its focal length is written under it."
        name == "MARK0" -> "□ FOCUS SQUARE" to "Shows or hides the focus square. A tap on the picture focuses there; pinch the square from an eye to the whole frame; FOC shows the distance."
        name == "MARK1" -> "○ EXPOSURE CIRCLE" to "Shows or hides the exposure circle: the part of the picture the exposure is measured on. Its own circle key sets what it does (ISO, white balance)."
        name == "MARK2" -> "△ MARK" to "Shows or hides the third mark on the picture."
        name == "AF" -> "AF · FOCUS" to "Automatic focus on or off. Off, the focus fader and the focus-puller scale hold it where you put it."
        name == "LOG" -> "LOG · CURVE" to "Steps through the picture curves: HLG (the phone's own, filmic), Sony S-Log3, Panasonic V-Log, ARRI LogC3 and LogC4, Blackmagic Film Gen5. The curve is in the file and on the stream."
        name == "M" -> "M · MODE" to "Automatic, half manual (HM: some faders yours, the rest the camera's) or full manual."
        name == "CTRL" -> "CTRL · FADERS" to "Shows the faders across the picture: ISO, shutter, focus, white balance, each with its own A/M switch."
        name == "FULL" -> "FULL · CLEAN FEED" to "Nothing on the screen but the picture: no keys, no meters, no system bars, for a screen copy or OBS. A double tap on the picture brings everything back."
        name == "LUT" -> "LUT" to "Switches through the LUTs loaded in settings, then off. Monitor only: the recording keeps its full range. A long press opens settings."
        name == "PLAY" || name == "play" -> "▶ PLAY" to "Opens the last take or photo, the round picture on the key."
        name == "camera" || name == "snap" -> "📷 PHOTO" to "Takes a still photo with the settings as they are."
        name == "REC" -> "● REC" to "Starts and stops a take. The timecode at the bottom runs while it records; the key turns red."
        name == "POR" -> "POR · PORTRAIT" to "The shooting is upright now. A tap turns it to LAN (landscape): the whole interface turns and every label stays upright."
        name == "LAN" -> "LAN · LANDSCAPE" to "The shooting is across now. A tap turns it to POR (portrait)."
        name == "PEA" -> "PEA · PEAKING" to "Focus peaking: the sharp edges lit in the colour chosen in settings. Monitor only."
        name == "FC" -> "FC · FALSE COLOUR" to "Exposure in false colour: middle grey green, skin pink, clipping red, crushed blacks purple. Monitor only."
        name == "ZEB" -> "ZEB · ZEBRA" to "Stripes over everything brighter than the zebra level set in settings (95 % by default). Monitor only."
        name.contains("GB") || name == "NO DRIVE" || name == "—" && power == null ->
            "FREE SPACE" to "The free space on the recording drive and how many hours and minutes of recording it holds at the present bit rate. Red under ten minutes. A tap opens settings."
        name.contains("%") -> "POWER MONITOR" to "The battery: its level with ▲ charging, ▼ discharging or = balanced, and under it the time left at this draw (or the time to full while charging). Measured on the cell's own charge counter over the last minutes. Red under 20 % or 20 minutes. A tap shows every number: mA, volts, watts, temperature." +
            (power?.let { "\n\nNow: " + PowerMonitor.Calc.describe(it) } ?: "")
        else -> name to "This key: $name."
    }
}
