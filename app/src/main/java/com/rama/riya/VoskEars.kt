package com.rama.riya

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * Offline speech-to-text using Vosk. Owns the microphone while running.
 * Audio is thrown away while [pause]d (so Riya never hears her own voice).
 */
class VoskEars(
    private val ctx: Context,
    private val modelName: String,
    private val onReady: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onText: (List<String>) -> Unit,
    private val onFail: (String) -> Unit
) {
    @Volatile private var alive = true
    @Volatile private var paused = true
    @Volatile private var resumeAt = 0L

    fun start() {
        val t = Thread { runLoop() }
        t.isDaemon = true
        t.start()
    }

    @Volatile var onStopWord: (() -> Unit)? = null
    @Volatile private var guarding = false
    /** While Riya speaks (mic paused), keep listening for just \"stop\". */
    fun guardStart() { guarding = true }
    fun guardEnd() { guarding = false }

    fun pause() { paused = true }

    fun resume() {
        if (paused) {
            resumeAt = System.currentTimeMillis() + 150   // skip the tail of Riya's own voice
            paused = false
        }
    }

    fun stop() { alive = false }

    private fun copyAssets(path: String, dest: File) {
        val am = ctx.assets
        val names = am.list(path)
        if (names == null || names.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(dest).use { out -> input.copyTo(out) } }
        } else {
            dest.mkdirs()
            for (n in names) copyAssets("$path/$n", File(dest, n))
        }
    }

    private fun prepareModel(): File {
        val src = "models/$modelName"
        val list = ctx.assets.list(src)
        if (list == null || list.isEmpty()) throw IllegalStateException("speech model '$modelName' is not inside the app")
        val dest = File(ctx.filesDir, "vosk/$modelName")
        val ok = File(dest, ".copied")
        if (!ok.exists()) {
            dest.deleteRecursively()
            copyAssets(src, dest)
            ok.writeText("1")
        }
        return dest
    }

    // With alternatives on, Vosk returns {"alternatives":[{"text":..},..]}; otherwise {"text":..}
    private fun parseTexts(json: String): List<String> {
        val o = JSONObject(json)
        val out = ArrayList<String>()
        val alts = o.optJSONArray("alternatives")
        if (alts != null) {
            for (i in 0 until alts.length()) {
                val t = alts.getJSONObject(i).optString("text").trim()
                if (t.isNotEmpty() && t !in out) out.add(t)
            }
        } else {
            val t = o.optString("text").trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    private fun runLoop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var guardRec: Recognizer? = null
        var mic: AudioRecord? = null
        try {
            val dir = prepareModel()
            val m = Model(dir.absolutePath)
            model = m
            val r = Recognizer(m, 16000.0f)
            rec = r
            guardRec = Recognizer(m, 16000.0f, StopWords.GRAMMAR)
            r.setMaxAlternatives(5)   // keep the 5 best guesses so a misheard name can still be matched

            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 16000 * 2)
            )
            mic = a
            if (a.state != AudioRecord.STATE_INITIALIZED) {
                if (alive) onFail("microphone could not be opened")
                return
            }
            a.startRecording()
            onReady()

            val buf = ShortArray(800)       // 50 ms chunks = quicker reaction
            var lastPartial = ""
            var lastChange = 0L
            var needReset = true
            var wasGuarding = false
            while (alive) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }
                if (paused || System.currentTimeMillis() < resumeAt) {
                    needReset = true
                    val gr = guardRec
                    if (paused && guarding && gr != null) {
                        if (!wasGuarding) { gr.reset(); wasGuarding = true }
                        val full = gr.acceptWaveForm(buf, n)
                        if (StopWords.isStop(if (full) gr.result else gr.partialResult)) {
                            guarding = false
                            gr.reset()
                            onStopWord?.invoke()
                        }
                    } else wasGuarding = false
                    continue
                }
                wasGuarding = false
                if (needReset) { r.reset(); lastPartial = ""; needReset = false }
                if (r.acceptWaveForm(buf, n)) {
                    val texts = parseTexts(r.result)
                    lastPartial = ""
                    if (texts.isNotEmpty()) onText(texts)
                } else {
                    val p = JSONObject(r.partialResult).optString("partial").trim()
                    val now = System.currentTimeMillis()
                    if (p.isNotEmpty() && p != lastPartial) {
                        lastPartial = p
                        lastChange = now
                        onPartial(p)
                    } else if (p.isNotEmpty() && now - lastChange > 300) {
                        // You stopped talking: don't wait for Vosk's slow silence detector, finish right now.
                        val texts = parseTexts(r.finalResult)
                        r.reset()
                        lastPartial = ""
                        if (texts.isNotEmpty()) onText(texts)
                    }
                }
            }
        } catch (e: Throwable) {
            if (alive) onFail(e.message ?: e.javaClass.simpleName)
        } finally {
            try { mic?.stop() } catch (e: Throwable) { }
            try { mic?.release() } catch (e: Throwable) { }
            try { rec?.close() } catch (e: Throwable) { }
            try { guardRec?.close() } catch (e: Throwable) { }
            try { model?.close() } catch (e: Throwable) { }
        }
    }
}
