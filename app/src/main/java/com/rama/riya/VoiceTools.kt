package com.rama.riya

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/** Picks the most human-sounding voice installed on the phone (Google's neural voices when available). */
object VoiceTools {

    fun english(tts: TextToSpeech): List<Voice> =
        try { tts.voices?.filter { it.locale.language == "en" && !it.features.contains("notInstalled") } ?: emptyList() }
        catch (e: Throwable) { emptyList() }

    private fun score(v: Voice): Int {
        var s = v.quality                                   // QUALITY_VERY_HIGH = 500 ... VERY_LOW = 100
        val n = v.name.lowercase()
        if (v.isNetworkConnectionRequired) s += 300         // Google's network voices are the neural, natural ones
        if (n.contains("neural") || n.contains("wavenet") || n.contains("studio")) s += 200
        if (v.locale.country == "IN") s += 150              // Indian-English accent suits Indian names
        else if (v.locale.country == "GB") s += 40
        else if (v.locale.country == "US") s += 30
        // Google en-IN female voices: ena, ene (others are male)
        if (n.contains("-ena-") || n.contains("-ene-") || n.contains("female")) s += 60
        return s
    }

    fun best(tts: TextToSpeech): Voice? = english(tts).maxByOrNull { score(it) }

    fun byName(tts: TextToSpeech, name: String): Voice? = english(tts).firstOrNull { it.name == name }

    /** Readable label for the picker. */
    fun label(v: Voice): String {
        val net = if (v.isNetworkConnectionRequired) " · natural (online)" else " · offline"
        return "${v.locale.displayName}  ${v.name.substringAfter("-x-", v.name).take(14)}$net"
    }

    fun sorted(tts: TextToSpeech): List<Voice> = english(tts).sortedByDescending { score(it) }
}
