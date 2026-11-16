package com.rama.riya

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.AlarmClock
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.BatteryManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiyaService : Service() {

    data class Contact(val name: String, val number: String)

    companion object {
        // Shown live on the main screen: stopped | listening | thinking | speaking
        @Volatile var state = "stopped"
        @Volatile var lastHeard = ""
        @Volatile var lastReply = ""
        @Volatile var lastError = ""
        @Volatile var lastHint = ""
        @Volatile var engineInfo = ""
        @Volatile var listener: (() -> Unit)? = null
    }

    private fun setState(st: String) { state = st; notifyUi() }
    private fun notifyUi() { handler.post { listener?.invoke() } }
    private fun prefs() = getSharedPreferences("riya", Context.MODE_PRIVATE)

    // Used when a contact number has no country code (change if you are not in India)
    private val defaultCountryCode = "91"

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    // When Riya asks "whom?", the next sentence is the answer (no wake word needed)
    private var pendingContacts: List<Contact>? = null
    private var pendingAction: ((Contact) -> Unit)? = null
    private val clearPending = Runnable { pendingContacts = null; pendingAction = null; pendingMisses = 0 }
    private var pendingMisses = 0

    // Speech bookkeeping: only the latest utterance may end "speaking", plus a watchdog
    // so a lost TTS callback can never leave Riya deaf.
    private var utteranceCounter = 0
    private var currentUtterance: String? = null
    private val speakWatchdog = Runnable { endGuard(); speaking = false; setState("listening"); resumeAfterSpeech() }

    // ---------- Stop while speaking / language ----------
    private var stopGuard: StopGuard? = null
    private var guardOn = false
    @Volatile private var aiSeq = 0            // bumps when the user says stop, so a late online answer is dropped
    private var curTtsLang = "en"

    private fun beginGuard() {
        if (guardOn) return
        guardOn = true
        val e = ears
        if (useVosk && e != null) e.guardStart() else stopGuard?.begin()
    }

    private fun endGuard() {
        if (!guardOn) return
        guardOn = false
        ears?.guardEnd()
        stopGuard?.end()
    }

    /** The user said "stop": be quiet right now and be ready for the next task. */
    private fun interruptSpeech() {
        aiSeq++
        if (!speaking && !thinking) return
        endGuard()
        handler.removeCallbacks(speakWatchdog)
        handler.removeCallbacks(llmWatchdog)
        currentUtterance = ""
        try { tts?.stop() } catch (e: Exception) { }
        thinking = false
        speaking = false
        afterSpeech = null
        pendingFreeText = null
        conversationUntil = System.currentTimeMillis() + 8000   // next sentence works even without my name
        setState("listening")
        notifyUi()
        startListening()
    }

    /** Switch the voice to the language of the answer. False = that voice is not installed on the phone. */
    private fun setSpeakLang(tag: String): Boolean {
        val t = tts ?: return true
        val loc = Locale.forLanguageTag(tag.ifBlank { "en-IN" })
        val key = loc.language
        if (key == curTtsLang) return true
        var r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = t.setLanguage(Locale(key))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale("en", "IN"))
            applyVoice()
            curTtsLang = "en"
            return false
        }
        curTtsLang = key
        if (key == "en") applyVoice()
        return true
    }

    private fun allowedLangs(): List<String> {
        val extra = (prefs().getString("langs", "te-IN,hi-IN") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf("en-IN") + extra).distinct()
    }

    /** Next time, listen in the language the user just used. */
    private fun noteLanguage(tag: String) {
        val lang = Locale.forLanguageTag(tag).language
        val match = allowedLangs().firstOrNull { Locale.forLanguageTag(it).language == lang } ?: return
        if (match != recLang) {
            recLang = match
            recognizerIntent = buildRecognizerIntent()
            resetRecognizer()
        }
    }

    private fun hasNonLatin(s: String) =
        s.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }

    private fun geminiKey() = prefs().getString("gemini_key", "").orEmpty().trim()
    private fun geminiReady() = geminiKey().isNotEmpty() && isOnline()

    private var overlayView: View? = null

    private var afterSpeech: (() -> Unit)? = null    // runs when the current sentence finishes

    private var useVosk = false
    private var ears: VoskEars? = null
    private var localLlm: LocalLlm? = null

    private var listening = false
    private var thinking = false
    private var conversationUntil = 0L          // after Riya talks, you can answer without the wake word
    private var currentFollowUp = false
    private var pendingFreeText: ((String) -> Unit)? = null
    private var freeUntil = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val aiHistory = ArrayList<Pair<String, String>>()
    private var appCache: List<AppEntry> = emptyList()
    private var appCacheAt = 0L

    private lateinit var wakeRegex: Regex
    private var assistantName = "riya"

    private fun loadWakeName() {
        assistantName = getSharedPreferences("riya", Context.MODE_PRIVATE)
            .getString("name", "riya")!!.trim().lowercase().ifEmpty { "riya" }
        val variants = when (assistantName) {
            "riya" -> listOf("riya", "ria", "reya", "rhea", "riyah", "riyaa", "reeya", "rea", "reah", "riyan")
            "janu" -> listOf("janu", "jaanu", "januu", "jhanu", "janoo", "zanu")
            "ram" -> listOf("ram", "raam", "rum", "rom", "ramm", "rahm")
            else -> listOf(assistantName)
        }
        wakeRegex = Regex("\\b(" + variants.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadWakeName()
        val channelId = "riya_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Riya", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName is listening")
            .setContentText("Say \"$assistantName\" followed by a command")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        // Keep the CPU awake so Riya keeps listening when the screen is off / locked
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:listen").apply { acquire() }

        addOverlay()
        muteBeeps()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lr = tts?.setLanguage(Locale("en", "IN"))
                if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
                applyVoice()
                tts?.setSpeechRate(1.15f)    // slightly quicker
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) = finishSpeaking(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeaking(utteranceId)
                    override fun onDone(utteranceId: String?) = finishSpeaking(utteranceId)
                })
                ttsReady = true
                stopGuard = StopGuard(this, prefs().getString("vosk_model", "en-in") ?: "en-in") { handler.post { interruptSpeech() } }
                say("Hi, I am $assistantName. I am listening.")
            }
        }
        if (prefs().getBoolean("offline_ears", false).not() && !SpeechRecognizer.isRecognitionAvailable(this)) {
            lastError = "No speech recognition on this phone. Install or update the Google app."
            notifyUi()
        }
        io.execute { installedApps() }   // warm the app list so "open ..." is instant

        localLlm = LocalLlm(this)
        if (LocalLlm.installed(this)) {
            io.execute {
                val err = localLlm?.load()
                if (err != null) { lastError = err; notifyUi() }
            }
        }
        registerCallWatcher()
        registerUnlockWatcher()
        registerScreenWatcher()
        if (!prefs().contains("ears_v17")) prefs().edit().putBoolean("offline_ears", false).putBoolean("ears_v17", true).apply()
        useVosk = prefs().getBoolean("offline_ears", false)
        engineInfo = if (useVosk) "Hearing: offline (Vosk " + (prefs().getString("vosk_model", "en-in") ?: "en-in") + ")" else "Hearing: Google speech"
        if (useVosk) startOfflineEars() else startListening()
    }

    // ---------- Incoming calls: read out who is calling (works offline) ----------

    private var callReceiver: BroadcastReceiver? = null
    @Volatile private var inCall = false
    private var ringing = false
    private var ringingNumber: String? = null
    private var ringRepeats = 0

    private fun registerCallWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val state = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                    @Suppress("DEPRECATION")
                    val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    handler.post { onCallState(state, num) }
                }
            }
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            callReceiver = r
        } catch (e: Exception) { /* call announcing is optional */ }
    }

    private fun onCallState(state: String, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!number.isNullOrBlank()) ringingNumber = number
                if (!ringing) {
                    ringing = true
                    ringRepeats = 0
                    handler.removeCallbacks(announceRunnable)
                    // the number often arrives in a second broadcast, so wait a moment
                    handler.postDelayed(announceRunnable, if (ringingNumber.isNullOrBlank()) 700L else 100L)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                ringing = false
                handler.removeCallbacks(announceRunnable)
                inCall = true                 // keep the mic free for the call
                ears?.pause()
                recognizer?.cancel()
                listening = false
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                ringingNumber = null
                handler.removeCallbacks(announceRunnable)
                if (inCall) {
                    inCall = false
                    handler.postDelayed({ startListening() }, 800L)
                }
            }
        }
    }

    private val announceRunnable = Runnable { announceCaller() }

    private fun announceCaller() {
        if (!ringing || !running) return
        val n = ringingNumber
        val name = if (!n.isNullOrBlank()) lookupContactName(n) else null
        val text = when {
            name != null -> "Incoming call from $name"
            !n.isNullOrBlank() -> "Incoming call from " + spellNumber(n)
            else -> "Incoming call from an unknown number"
        }
        say(text)
        ringRepeats++
        if (ringRepeats < 3) handler.postDelayed(announceRunnable, 8000L)
    }

    private fun lookupContactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    /** "+91 98765 43210" -> "9 8 7 6 5, 4 3 2 1 0" so the voice reads digit by digit. */
    private fun spellNumber(n: String): String {
        val digits = n.filter { it.isDigit() }
        val national = if (digits.length > 10) digits.takeLast(10) else digits
        return national.chunked(5).joinToString(", ") { it.toCharArray().joinToString(" ") }
    }

    private fun answerCall() {
        if (!ringing) { say("No call is ringing right now.", true); return }
        try {
            if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(TelecomManager::class.java).acceptRingingCall()
                return
            }
        } catch (e: Exception) { }
        say("Please allow phone permission in the Riya app so I can answer calls.", true)
    }

    private fun rejectCall() {
        if (!ringing && !inCall) { say("There is no call right now.", true); return }
        try {
            if (Build.VERSION.SDK_INT >= 28 &&
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                getSystemService(TelecomManager::class.java).endCall()
                return
            }
        } catch (e: Exception) { }
        say("I could not end the call. Please allow phone permission in the Riya app.", true)
    }

    // ---------- Offline hearing (Vosk) ----------

    private fun startOfflineEars() {
        val model = prefs().getString("vosk_model", "en-in") ?: "en-in"
        lastError = "Loading offline hearing…"
        notifyUi()
        val e = VoskEars(
            this, model,
            onReady = {
                handler.post {
                    if (!running) return@post
                    lastError = ""
                    notifyUi()
                    startListening()
                }
            },
            onPartial = { p ->
                handler.post { if (running && !speaking && !thinking) { lastHeard = p; notifyUi() } }
            },
            onText = { t ->
                handler.post {
                    if (running && !speaking && !thinking) {
                        lastHeard = t[0]
                        notifyUi()
                        handleSpeech(t)
                    }
                }
            },
            onFail = { msg ->
                handler.post {
                    if (!running) return@post
                    lastError = "Offline hearing failed ($msg). Using the Google recogniser instead."
                    useVosk = false
                    engineInfo = "Hearing: Google speech"
                    ears?.stop()
                    ears = null
                    notifyUi()
                    startListening()
                }
            }
        )
        e.onStopWord = { handler.post { interruptSpeech() } }
        ears = e
        e.start()
    }

    // The speech recogniser plays a "ding" each time it starts listening. Mute the system and
    // notification streams while Riya runs (restored when Riya stops).
    private val mutedStreams = ArrayList<Int>()

    private fun muteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            } catch (e: Exception) { /* needs Do Not Disturb access on some phones */ }
        }
    }

    private fun unmuteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in mutedStreams) {
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        mutedStreams.clear()
    }

    // A 1px invisible overlay lets Android allow starting apps/calls from this background service.
    private fun addOverlay() {
        try {
            if (!Settings.canDrawOverlays(this) || overlayView != null) return
            val wm = getSystemService(WindowManager::class.java)
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp)
            overlayView = v
        } catch (e: Exception) { /* optional helper */ }
    }

    // ---------- Listening (one reused recognizer = much faster restarts) ----------

    private var recLang = "en-IN"
    // built later (needs the app context, which does not exist yet while the service object is being created)
    private lateinit var recognizerIntent: Intent

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            val langs = ArrayList(allowedLangs())
            if (langs.size > 1) {   // Android 13+ Google speech: pick the spoken language automatically
                putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
                putStringArrayListExtra("android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES", langs)
                putExtra("android.speech.extra.ENABLE_LANGUAGE_SWITCH", "balanced")
                putStringArrayListExtra("android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES", langs)
            }
            val names = contactNames()
            if (names.isNotEmpty()) putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", names)  // Android 13+
        }

    // Use the voice you picked in the app; otherwise the most natural one on the phone
    private fun applyVoice() {
        val t = tts ?: return
        try {
            val saved = prefs().getString("tts_voice", "") ?: ""
            val v = (if (saved.isNotEmpty()) VoiceTools.byName(t, saved) else null) ?: VoiceTools.best(t)
            if (v != null) t.voice = v
        } catch (e: Throwable) { }
    }

    private fun errorName(e: Int) = when (e) {
        1 -> "network timeout"; 2 -> "network error"; 3 -> "audio problem"; 4 -> "server error"
        5 -> "client error"; 8 -> "recogniser busy"; 9 -> "microphone permission missing"
        10 -> "too many requests"; 11 -> "server disconnected"
        12 -> "language not supported"; 13 -> "language not available"
        else -> "error $e"
    }

    private val listenWatchdog = Runnable { listening = false; resetRecognizer(); startListening() }

    private val recListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (heard.isNotEmpty()) {
                pendingMisses = 0
                lastHeard = heard[0]
                notifyUi()
                handleSpeech(heard)
            }
            restart()
        }

        override fun onError(error: Int) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val soft = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!soft) {
                lastError = "Mic problem: " + errorName(error)
                notifyUi()
                if ((error == 12 || error == 13) && recLang != "en-US") {
                    recLang = "en-US"
                    recognizerIntent = buildRecognizerIntent()
                }
                resetRecognizer()
            }
            if (pendingContacts != null && soft) {
                pendingMisses++
                if (pendingMisses >= 3) {
                    pendingMisses = 0
                    say("I did not hear you. Please say the number, like one, two or three.")
                }
            }
            restart(if (soft) 60L else 300L)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (lastError.isNotEmpty()) { lastError = ""; notifyUi() }
            if (!thinking && !speaking) setState("listening")
        }

        // Fastest path: while choosing a contact, react the moment a clear number is heard
        override fun onPartialResults(partialResults: Bundle?) {
            val pending = pendingContacts ?: return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: return
            if (p.any { parseChoice(it, pending.size, strictOnly = true) != null }) {
                listening = false
                handler.removeCallbacks(listenWatchdog)
                recognizer?.cancel()
                lastHeard = p[0]; notifyUi()
                handleChoice(p, pending)
                restart()
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun startListening() {
        if (!running || speaking || inCall) return
        if (useVosk) {
            ears?.resume()
            if (!thinking) setState("listening")
            return
        }
        if (listening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recListener) }
        }
        listening = true
        handler.removeCallbacks(listenWatchdog)
        handler.postDelayed(listenWatchdog, 20000)
        recognizerIntent = buildRecognizerIntent()   // refresh contact-name hints
        recognizer?.startListening(recognizerIntent)
    }

    private fun restart(delay: Long = 60) {
        if (running && !speaking) handler.postDelayed({ startListening() }, if (pendingContacts != null) 0L else delay)
    }

    // ---------- Speaking ----------

    private fun say(msg: String, followUp: Boolean = false, lang: String = "en-IN") {
        lastReply = msg
        if (!ttsReady) {
            if (followUp) conversationUntil = System.currentTimeMillis() + 8000
            notifyUi()
            val h = afterSpeech; afterSpeech = null
            h?.invoke()
            return
        }
        speaking = true
        setState("speaking")
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(listenWatchdog)
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = followUp
        handler.removeCallbacks(speakWatchdog)
        var spoken = msg
        if (!setSpeakLang(lang)) {
            spoken = "I do not have a " + Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH) +
                " voice on this phone. Please install it in the text to speech settings."
        }
        val r = tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            val h = afterSpeech; afterSpeech = null
            if (h != null) h() else restart()
        } else {
            beginGuard()
            handler.postDelayed(speakWatchdog, 4000L + spoken.length * 90L)
        }
    }

    private fun finishSpeaking(id: String?) {
        handler.post {
            if (id != currentUtterance) return@post
            handler.removeCallbacks(speakWatchdog)
            endGuard()
            speaking = false
            conversationUntil = if (currentFollowUp) System.currentTimeMillis() + 8000 else 0L
            if (pendingContacts != null) armPending()
            setState("listening")
            resumeAfterSpeech()
        }
    }

    private fun resumeAfterSpeech() {
        val h = afterSpeech
        afterSpeech = null
        if (h != null) h() else startListening()
    }

    private fun armPending() {
        handler.removeCallbacks(clearPending)
        handler.postDelayed(clearPending, 30000)
    }

    // ---------- Command handling ----------

    @Volatile private var heardAlts: List<String> = emptyList()

    private fun handleSpeech(heard: List<String>) {
        heardAlts = heard
        val pending = pendingContacts
        if (pending != null) {
            handleChoice(heard, pending)
            return
        }
        val now = System.currentTimeMillis()
        val free = pendingFreeText
        if (free != null) {
            pendingFreeText = null
            if (now < freeUntil) { free(heard[0]); return }
        }
        // Telugu, Hindi, Tamil ... (non-English script): Gemini understands it, runs the command or answers in that language
        if (hasNonLatin(heard[0]) && geminiReady() &&
            (!prefs().getBoolean("wake_required", false) || now < conversationUntil)) {
            conversationUntil = 0
            askGemini(heard[0], localLlm?.isLoaded() == true, true)
            return
        }
        for (text in heard) {
            val match = wakeRegex.find(text) ?: continue
            val after = text.substring(match.range.last + 1).trim()
            val before = text.substring(0, match.range.first).trim()
            conversationUntil = 0
            handle(if (after.isNotEmpty()) after else before)   // "Riya call Vinod" and "call Vinod Riya"
            return
        }
        // No wake word, but Riya just spoke to you: treat this as the reply
        if (now < conversationUntil) { conversationUntil = 0; handle(trimToCommand(heard[0])); return }
        val t = heard[0].trim()
        val words = t.split(" ").filter { it.isNotEmpty() }
        // The speech engine often writes the name wrongly ("ria", "area"). A short call like that = "I am listening"
        if (words.size <= 2 && words.any { isCallWord(it) }) {
            conversationUntil = 0
            handle("")
            return
        }
        // "area open instagram": drop 1-2 mis-heard name words in front of a real command
        val trimmed = trimToCommand(t)
        if (trimmed != t) { handle(trimmed); return }
        // Wake word turned off in the app: every sentence is a command
        if (!prefs().getBoolean("wake_required", false)) {
            val noise = setOf("the", "a", "uh", "huh", "hmm", "um", "oh", "ah", "eh", "and", "but")
            if (t.length >= 3 && t !in noise) handle(t, false)
            return
        }
        // Heard you, but there was no name: tell the user instead of staying silent
        lastHint = "Heard \u201c${heard[0]}\u201d. Start with \u201c$assistantName\u201d, e.g. \u201c$assistantName, open Instagram\u201d."
        notifyUi()
    }

    private val verbStart = Regex(
        "^(open|launch|start|run|call|phone|dial|play|message|send|close|clear|kill|remove|clean|go|press|show|take|set|turn|switch|" +
        "volume|mute|unmute|increase|decrease|flashlight|torch|lock|stop|exit|wake|what|whats|tell|how|who|why|when|where)\\b"
    )

    /** "area open instagram" -> "open instagram" (only when 1-2 stray words sit in front of a real command). */
    private fun trimToCommand(t: String): String {
        if (verbStart.containsMatchIn(t)) return t
        val words = t.split(" ").filter { it.isNotEmpty() }
        for (k in 1..minOf(2, words.size - 1)) {
            val rest = words.drop(k).joinToString(" ")
            if (verbStart.containsMatchIn(rest)) return rest
        }
        return t
    }

    private fun isCallWord(w: String): Boolean {
        val extra = if (assistantName == "riya")
            setOf("ria", "reya", "rhea", "riyah", "riyaa", "rear", "area", "aria", "arya", "ariya", "rio", "reah", "real") else emptySet()
        return w == assistantName || w in extra || (assistantName.length >= 4 && editDistance(w, assistantName) <= 1)
    }

    private fun handle(raw: String, addressed: Boolean = true) {
        lastHint = ""
        var c = raw.trim()
        // speech engines write the app name oddly: "open what's up" -> whatsapp
        c = c.replace(Regex("\\b(open|launch|start|on|to) (what'?s ?up|what sap|whats ?app|watsapp|what's ?app)\\b"), "$1 whatsapp")
        val filler = Regex("^(please|can you|could you|would you|will you|hey|ok|okay|just|now)\\b[ ,]*")
        var prev: String
        do { prev = c; c = c.replace(filler, "").trim() } while (c != prev)
        if (c.isEmpty()) {
            val o = prefs().getString("owner", "").orEmpty().trim()
            say(if (o.isEmpty()) "I am listening. Tell me." else "$o, I am listening. Tell me.", followUp = true)
            return
        }

        val openRe = Regex("^(open|launch|start|run)\\s+(?:the\\s+)?(.+)$")
        if (isPhoneLocked() && (openRe.containsMatchIn(c) || c.startsWith("play") || "whatsapp" in c || "camera" in c)) {
            say("The phone is locked. Please unlock it first, then ask me again.", true)
            return
        }

        when {
            c == "exit" || c == "quit" || c.startsWith("stop listening") || c.startsWith("turn off") ||
                c.startsWith("shut down") || c.startsWith("exit $assistantName") -> stopSelf()
            Regex("^(stop|wait|enough|cancel|quiet|silence|shut up)( it| talking| speaking| please)?( $assistantName)?$").containsMatchIn(c) ->
                interruptSpeech()
            Regex("^(call|phone|dial)\\b").containsMatchIn(c) -> {
                val name = c.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                if (name.isEmpty()) {
                    pendingFreeText = { n ->
                        val nm = n.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                        if (nm.isNotEmpty()) pickContact(nm) { dial(it) }
                    }
                    freeUntil = System.currentTimeMillis() + 15000
                    say("Whom should I call?")
                } else pickContact(name) { dial(it) }
            }
            Regex("^(answer|pick up|pickup|accept)( the| this)?( call| phone)?$").containsMatchIn(c) -> answerCall()
            Regex("^(reject|decline|cut|end|hang up|disconnect)( the| this)?( call| phone)?$").containsMatchIn(c) -> rejectCall()
            Regex("^(close|clear|kill|remove|clean)\\b.*\\b(apps|applications|recents|recent|background|tasks)\\b").containsMatchIn(c) ||
                Regex("^(close|clear) all$").containsMatchIn(c) -> closeAllApps()
            Regex("^(go )?(to )?(the )?home( screen)?$").containsMatchIn(c) || c == "go home" ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            Regex("^(go |press )?back$").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            Regex("\\b(recent apps|recents|app switcher|recent screen)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
            Regex("\\b(notifications?|notification panel|notification bar)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
            "quick settings" in c ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
            Regex("\\b(screenshot|screen shot)\\b").containsMatchIn(c) ->
                sysAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot")
            Regex("\\b(alarm|wake me)\\b").containsMatchIn(c) -> setAlarm(c)
            Regex("\\btimer\\b").containsMatchIn(c) -> setTimer(c)
            Regex("\\b(wi ?fi|wi-fi|bluetooth|brightness|airplane mode|flight mode|hotspot|location settings|gps)\\b").containsMatchIn(c) &&
                !c.startsWith("play") -> openSettingsFor(c)
            openRe.containsMatchIn(c) -> {
                val target = openRe.find(c)!!.groupValues[2].replace(Regex("\\s+app$"), "").trim()
                if ("camera" in target) openCamera() else openApp(target)
            }
            c.startsWith("play") -> {
                val m = Regex("^play (.+?)(?: on (youtube|spotify))?$").find(c)
                val song = m?.groupValues?.get(1) ?: c.replace("play", "").trim()
                val platform = m?.groupValues?.get(2).orEmpty()
                if (!isOnline()) playLocal(song)
                else if (platform == "youtube") youtube(song) else playSpotify(song)
            }
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> whatsapp(c)
            Regex("\\b(unlock|un lock)\\b").containsMatchIn(c) ->
                say("Sorry, I can no longer unlock the phone. Please unlock it yourself.", true)
            Regex("\\block\\b").containsMatchIn(c) && "unlock" !in c -> lockScreen()
            "flashlight" in c || "torch" in c || "flash light" in c -> flashlight(c)
            "volume" in c || "mute" in c || "louder" in c -> volume(c)
            Regex("^(what('?s| is) )?(the )?(current )?time( now| please)?$|\\bwhat time\\b|\\btime is it\\b|\\bthe time\\b").containsMatchIn(c) ->
                say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), true)
            Regex("\\b(what('?s| is) (the )?(date|day)|today'?s date|what day|which day|the date)\\b").containsMatchIn(c) ->
                say("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), true)
            "camera" in c -> openCamera()
            else -> {
                val talk = smallTalk(c)
                if (talk != null) say(talk, true)
                else if (addressed && c.split(" ").size <= 3 && openApp(c, quiet = true)) { /* opened */ }
                else answerFreeText(c, addressed)
            }
        }
    }

    // ---------- Chat: offline small talk + optional AI brain ----------

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter!",
        "Why was the computer cold? It left its Windows open.",
        "I told my phone a joke, but it did not laugh. It just buffered.",
        "Why do programmers prefer dark mode? Because light attracts bugs."
    )

    private fun smallTalk(c: String): String? {
        fun has(vararg w: String) = w.any { Regex("\\b" + it + "\\b").containsMatchIn(c) }
        val short = c.split(" ").size <= 3
        return when {
            short && has("hi", "hii", "hello", "hey", "namaste") -> "Hello! How can I help you?"
            "how are you" in c -> "I am doing great, thanks for asking! What can I do for you?"
            "your name" in c || "who are you" in c -> "I am $assistantName, your voice assistant."
            has("thanks", "thank you") -> "You are welcome!"
            "good morning" in c -> "Good morning! Have a wonderful day."
            "good night" in c -> "Good night! Sleep well."
            "joke" in c -> jokes.random()
            "i love you" in c -> "Aww, that is sweet! I am always here to help."
            has("bye", "goodbye") -> "Bye! Call me anytime."
            "battery" in c -> {
                val bm = getSystemService(BatteryManager::class.java)
                "Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent"
            }
            "what can you do" in c || c == "help" ->
                "I can open any app, call your contacts, send WhatsApp messages, play songs, control the flashlight and volume, lock your screen, and chat with you."
            else -> null
        }
    }

    private fun needsLiveData(c: String) = Regex(
        "\\b(weather|temperature|forecast|news|headlines|score|stock|share price|price of|exchange rate|latest|trending|who won|live)\\b"
    ).containsMatchIn(c)

    /** Picks the right brain: online AI for live facts, the on-phone brain for everything else (works offline). */
    private fun answerFreeText(c: String, addressed: Boolean) {
        val hasKey = prefs().getString("ai_key", "").orEmpty().isNotBlank()
        val online = isOnline()
        val installed = LocalLlm.installed(this)
        val loaded = localLlm?.isLoaded() == true
        val cloudOk = hasKey && online
        when {
            geminiReady() -> askGemini(c, loaded, false)
            needsLiveData(c) && !cloudOk ->
                say("I need internet and an AI key in the app for live information like that.", true)
            cloudOk && (needsLiveData(c) || !installed) -> askAi(c, loaded)
            loaded -> askLocal(c)
            installed ->
                say("My offline brain is still loading. Please ask again in a few seconds.", true)
            addressed ->
                say("Sorry, I did not understand. Install the offline brain in the app so I can answer anything.", true)
        }
    }

    private val streamBuf = StringBuilder()
    private var streamSpoken = 0
    private var streamAny = false
    private var streamQuestion = ""
    private val sentenceEnd = Regex("[.!?]\\s|\\n")
    private val llmWatchdog = Runnable { onLlmPart("", true) }

    private fun askLocal(q: String) {
        thinking = true
        ears?.pause()
        setState("thinking")
        streamBuf.setLength(0)
        streamSpoken = 0
        streamAny = false
        streamQuestion = q
        lastReply = ""
        val past = aiHistory.takeLast(2).toList()
        val started = localLlm?.askStream(assistantName, past, q) { part, done ->
            handler.post { onLlmPart(part, done) }
        } ?: false
        if (started) {
            handler.removeCallbacks(llmWatchdog)
            handler.postDelayed(llmWatchdog, 60000L)
        } else {
            thinking = false
            say("Sorry, I could not think of an answer.", true)
        }
    }

    /** Speaks each sentence as soon as the model has written it, so the answer starts almost immediately. */
    private fun onLlmPart(part: String, done: Boolean) {
        if (!thinking || !running) return
        streamBuf.append(part)
        var text = streamBuf.toString()
        var end = done
        val cut = text.indexOf("<|")
        if (cut >= 0) { text = text.substring(0, cut); end = true }
        while (true) {
            val m = sentenceEnd.find(text, streamSpoken) ?: break
            val stop = m.range.last + 1
            val sentence = text.substring(streamSpoken, stop)
            streamSpoken = stop
            speakChunk(sentence)
        }
        if (end) {
            handler.removeCallbacks(llmWatchdog)
            val rest = if (streamSpoken < text.length) text.substring(streamSpoken) else ""
            speakChunk(rest)
            thinking = false
            if (!streamAny) {
                say("Sorry, I could not think of an answer.", true)
            } else {
                aiHistory.add("user" to streamQuestion)
                aiHistory.add("assistant" to text.trim())
                while (aiHistory.size > 12) aiHistory.removeAt(0)
            }
        }
    }

    private fun speakChunk(raw: String) {
        val msg = raw.replace("*", "").replace("#", "").replace("_", " ").trim()
        if (msg.count { it.isLetter() } < 2) return
        val first = !streamAny
        streamAny = true
        lastReply = if (first) msg else lastReply + " " + msg
        notifyUi()
        if (!ttsReady) return
        if (first) setSpeakLang("en-IN")
        speaking = true
        setState("speaking")
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(speakWatchdog)
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = true
        val r = tts?.speak(msg, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            startListening()
        } else {
            beginGuard()
            handler.postDelayed(speakWatchdog, 6000L + msg.length * 90L)
        }
    }

    private fun askGemini(q: String, fallbackLocal: Boolean, allowCommand: Boolean) {
        val key = geminiKey()
        thinking = true
        setState("thinking")
        val my = ++aiSeq
        val past = aiHistory.takeLast(8).toList()
        io.execute {
            val reply = try { GeminiBrain.ask(key, assistantName, past, q) } catch (e: Exception) { null }
            handler.post {
                if (my != aiSeq) return@post        // the user said stop meanwhile
                thinking = false
                if (!running) return@post
                if (reply == null || (reply.text.isBlank() && reply.command.isBlank())) {
                    if (fallbackLocal) askLocal(q)
                    else say("Sorry, I could not reach my brain. Please check your internet and Gemini key.", true)
                    return@post
                }
                if (reply.lang.isNotBlank()) noteLanguage(reply.lang)
                if (allowCommand && reply.command.isNotBlank()) {
                    handle(reply.command)           // \"call dwaraka\" said in Telugu
                    return@post
                }
                aiHistory.add("user" to q)
                aiHistory.add("assistant" to reply.text)
                while (aiHistory.size > 20) aiHistory.removeAt(0)
                say(reply.text, true, reply.lang)
            }
        }
    }

    private fun askAi(q: String, fallbackLocal: Boolean = false) {
        val key = prefs().getString("ai_key", "").orEmpty().trim()
        thinking = true
        setState("thinking")
        val past = aiHistory.takeLast(8).toList()
        io.execute {
            val reply = try { callClaude(key, past, q) } catch (e: Exception) { null }
            handler.post {
                thinking = false
                if (!running) return@post
                if (reply.isNullOrBlank()) {
                    if (fallbackLocal) askLocal(q)
                    else say("Sorry, I could not reach my brain. Please check your internet and key.", true)
                } else {
                    aiHistory.add("user" to q)
                    aiHistory.add("assistant" to reply)
                    while (aiHistory.size > 20) aiHistory.removeAt(0)
                    say(reply, true)
                }
            }
        }
    }

    private fun callClaude(key: String, past: List<Pair<String, String>>, q: String): String? {
        val msgs = JSONArray()
        for ((role, text) in past) msgs.put(JSONObject().put("role", role).put("content", text))
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 150)
            .put("system", "You are $assistantName, a friendly voice assistant on the user's phone. " +
                "Reply in one or two short spoken sentences. No markdown, no lists, no emojis.")
            .put("messages", msgs)
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Open ANY app by name ----------

    private data class AppEntry(val label: String, val norm: String, val pkg: String)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val appAliases = mapOf(
        "insta" to "instagram", "fb" to "facebook", "gpay" to "googlepay", "googlepay" to "gpay",
        "yt" to "youtube", "maps" to "maps", "gmail" to "gmail", "mail" to "gmail",
        "settings" to "settings", "setting" to "settings", "whatsapp" to "whatsapp",
        "chrome" to "chrome", "browser" to "chrome", "play store" to "playstore",
        "playstore" to "playstore", "photos" to "photos", "gallery" to "gallery",
        "calculator" to "calculator", "phonepe" to "phonepe", "paytm" to "paytm",
        "telegram" to "telegram", "snapchat" to "snapchat", "snap" to "snapchat",
        "twitter" to "x", "insta gram" to "instagram", "what's app" to "whatsapp"
    )

    @Synchronized
    private fun installedApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < 60000) return appCache
        appCache = loadInstalledApps()
        appCacheAt = now
        return appCache
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map {
                val label = it.loadLabel(packageManager).toString()
                AppEntry(label, norm(label), it.activityInfo.packageName)
            }
            .filter { it.pkg != packageName && it.norm.isNotEmpty() }
            .distinctBy { it.pkg }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun findApp(spoken: String): AppEntry? {
        val apps = installedApps()
        val rawTarget = norm(spoken)
        if (rawTarget.isEmpty()) return null
        val targets = listOfNotNull(rawTarget, appAliases[spoken.trim()], appAliases[rawTarget]).distinct()

        for (t in targets) {
            apps.firstOrNull { it.norm == t }?.let { return it }
        }
        for (t in targets) {
            apps.filter { it.norm.startsWith(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        for (t in targets) {
            if (t.length >= 3) apps.filter { it.norm.contains(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        // speech-recognition slips ("whats up" / "spotifi"): closest spelling wins
        val t = rawTarget
        val best = apps.map { it to editDistance(t, it.norm) }.minByOrNull { it.second }
        if (best != null) {
            val limit = maxOf(1, maxOf(t.length, best.first.norm.length) / 4)
            if (best.second <= limit) return best.first
        }
        return null
    }

    private fun openApp(spoken: String, quiet: Boolean = false): Boolean {
        val app = findApp(spoken)
        if (app == null) {
            if (!quiet) say("I could not find an app named $spoken")
            return false
        }
        val intent = packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent != null && launch(intent)) {
            say("Opening ${app.label}")
            return true
        }
        if (!quiet) say("I could not open ${app.label}")
        return false
    }

    // ---------- Contacts: search + ask whom ----------

    private fun queryContacts(like: String): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$like%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val key = num.filter { it.isDigit() }.takeLast(10)
                if (key.isNotEmpty() && !result.containsKey(key)) result[key] = Contact(n, num)
            }
        }
        return result.values.toList()
    }

    private fun findContacts(name: String): List<Contact> {
        var r = queryContacts(name)
        if (r.isEmpty()) {
            // "vinod kumar" not saved that way? try the individual words
            for (w in name.split(" ").filter { it.length >= 3 }.sortedByDescending { it.length }) {
                r = queryContacts(w)
                if (r.isNotEmpty()) break
            }
        }
        if (r.isEmpty()) r = fuzzyContacts(name)   // misheard name: match by sound
        return r
    }

    // Compare how names SOUND ("the worker" / "dwarka" -> Dwaraka)
    private fun fuzzyContacts(spoken: String): List<Contact> {
        val all = queryContacts("")
        if (all.isEmpty()) return all
        val names = all.map { it.name }
        // the phrase as heard + every other guess of the engine, with "call to" stripped off
        val callWord = Regex("^.*?\\b(call|phone|dial)( to)?\\b")
        val guesses = (listOf(spoken) + heardAlts.map { it.replace(callWord, "").trim() })
            .filter { it.isNotBlank() }.distinct()
        var bestSet: Set<String> = emptySet()
        var bestScore = 0.0
        for (g in guesses) {
            val r = ContactMatcher.rank(g, names)
            if (r.isNotEmpty() && r[0].second > bestScore) { bestScore = r[0].second; bestSet = r.map { it.first }.toSet() }
        }
        return all.filter { it.name in bestSet }
    }

    // Contact names, cached, used to teach the Google recogniser the names you say
    @Volatile private var nameCache: List<String> = emptyList()
    @Volatile private var nameCacheAt = 0L
    private fun contactNames(): ArrayList<String> {
        try {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ArrayList()
        } catch (e: Throwable) { return ArrayList() }
        if (System.currentTimeMillis() - nameCacheAt > 300_000 || nameCache.isEmpty()) {
            try {
                val set = LinkedHashSet<String>()
                contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use {
                    while (it.moveToNext() && set.size < 1000) it.getString(0)?.let { n -> set.add(n) }
                }
                nameCache = set.toList(); nameCacheAt = System.currentTimeMillis()
            } catch (e: Exception) { }
        }
        return ArrayList(nameCache)
    }

    private fun pickContact(name: String, action: (Contact) -> Unit) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission in the Riya app"); return
        }
        val list = findContacts(name)
        when {
            list.isEmpty() -> say("I could not find $name in your contacts")
            list.size == 1 -> action(list[0])
            else -> {
                val shown = list.take(6)
                pendingContacts = shown
                pendingAction = action
                pendingMisses = 0
                armPending()
                val options = shown.mapIndexed { i, ct -> "${i + 1}, ${labelFor(ct, shown)}" }.joinToString(". ")
                say("I found ${list.size} contacts. $options. Say the number.")
            }
        }
    }

    private fun labelFor(c: Contact, all: List<Contact>): String =
        if (all.count { it.name == c.name } > 1)
            "${c.name}, number ending ${c.number.filter { it.isDigit() }.takeLast(2)}"
        else c.name

    // Clear number words first; sound-alikes only if nothing clear was heard
    private val strictNumbers = mapOf(
        "one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3,
        "four" to 4, "fourth" to 4, "five" to 5, "fifth" to 5, "six" to 6, "sixth" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val soundAlikes = mapOf(
        "won" to 1, "wan" to 1, "to" to 2, "too" to 2, "tu" to 2, "tree" to 3, "free" to 3, "tri" to 3, "thri" to 3,
        "for" to 4, "fore" to 4, "far" to 4, "fife" to 5, "hive" to 5, "sex" to 6, "ate" to 8
    )

    private fun parseChoice(text: String, size: Int, strictOnly: Boolean = false): Int? {
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if ("last" in tokens) return size
        for (t in tokens) {
            val digits = Regex("^(\\d+)(st|nd|rd|th)?$").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val n = digits ?: strictNumbers[t]
            if (n != null && n in 1..size) return n
        }
        if (strictOnly) return null
        for (t in tokens) {
            val n = soundAlikes[t]
            if (n != null && n in 1..size) return n
        }
        return null
    }

    private val choiceStopWords = setOf("call", "the", "please", "number", "want", "i", "to", "for", "a", "and", "of", "that", "this", "one", "ok", "okay")

    private fun handleChoice(heard: List<String>, list: List<Contact>) {
        if (heard.any { Regex("\\b(cancel|nobody|none|never mind|stop)\\b").containsMatchIn(it) }) {
            clearPending.run(); handler.removeCallbacks(clearPending)
            say("Okay, cancelled")
            return
        }
        var chosen: Contact? = null
        for (text in heard) {
            val idx = parseChoice(text, list.size)
            if (idx != null) { chosen = list[idx - 1]; break }
        }
        if (chosen == null) {
            for (text in heard) {
                val words = text.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in choiceStopWords }
                if (words.isEmpty()) continue
                val matches = list.filter { c -> words.all { c.name.lowercase().contains(it) } }
                if (matches.size == 1) { chosen = matches[0]; break }
            }
        }
        if (chosen != null) {
            val action = pendingAction
            clearPending.run(); handler.removeCallbacks(clearPending)
            action?.invoke(chosen)
        } else {
            armPending()
            say("Sorry, say the number, like one, two or three")
        }
    }

    // ---------- Call ----------

    private fun dial(c: Contact) {
        say("Calling ${c.name}")
        val uri = Uri.fromParts("tel", c.number, null)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                // TelecomManager places the call even when the phone is locked
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    // ---------- WhatsApp ----------

    private fun whatsapp(c: String) {
        val cleaned = c.replace(Regex("^(send )?(a )?(whatsapp )?(message )?(on whatsapp )?(to )?"), "").trim()
        val m = Regex("^(.+?) (?:saying|that|message|as) (.+)$").find(cleaned)
        if (m == null) {
            say("Say it like: Riya send WhatsApp to Vinod saying hello")
            return
        }
        val name = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        pickContact(name) { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            say("Sending WhatsApp to ${contact.name}")
            RiyaAccessibility.arm()
            val url = "https://wa.me/$digits?text=" + Uri.encode(text)
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ---------- Flashlight ----------

    private fun flashlight(c: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { say("No flashlight found"); return }
        torchOn = if ("off" in c) false else if ("on" in c) true else !torchOn
        try {
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Could not control the flashlight") }
    }

    // ---------- Offline phone controls ----------

    private val numWords = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val tensWords = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /** Pulls numbers out of speech, both "7 30" and "seven thirty" / "twenty five". */
    private fun parseNums(text: String): List<Int> {
        val out = ArrayList<Int>()
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val digits = t.toIntOrNull()
            val tens = tensWords[t]
            val unit = numWords[t]
            if (digits != null) {
                out.add(digits)
            } else if (tens != null) {
                var v: Int = tens
                if (i + 1 < tokens.size) {
                    val u = numWords[tokens[i + 1]]
                    if (u != null && u in 1..9) { v += u; i++ }
                }
                out.add(v)
            } else if (unit != null && t != "oh") {
                out.add(unit)
            } else if (t == "oh") {
                out.add(0)
            }
            i++
        }
        return out
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { true }
    }

    private fun sysAction(action: Int, doneMsg: String) {
        val acc = RiyaAccessibility.instance
        if (acc == null) {
            say("Please turn on Riya in Accessibility settings first.", true)
            return
        }
        acc.performGlobalAction(action)
        say(doneMsg)
    }

    // ---------- Screen off / locked: Google speech goes quiet, so use the offline mic then ----------
    private var screenReceiver: BroadcastReceiver? = null
    private var switchedForScreen = false

    private fun registerScreenWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> handler.post { onScreenOff() }
                        Intent.ACTION_USER_PRESENT -> handler.post { onScreenOn() }
                    }
                }
            }
            val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            screenReceiver = r
        } catch (e: Exception) { }
    }

    private fun onScreenOff() {
        if (!running || useVosk) return
        handler.removeCallbacks(listenWatchdog)
        try { recognizer?.cancel() } catch (e: Exception) { }
        resetRecognizer()
        switchedForScreen = true
        useVosk = true
        engineInfo = "Hearing: offline (screen off)"
        startOfflineEars()
    }

    private fun onScreenOn() {
        if (!running || !switchedForScreen) return
        switchedForScreen = false
        ears?.stop()
        ears = null
        useVosk = prefs().getBoolean("offline_ears", false)
        handler.postDelayed({
            if (!running) return@postDelayed
            if (useVosk) startOfflineEars()
            else { engineInfo = "Hearing: Google speech"; startListening() }
        }, 400L)
    }

    @Volatile private var clearOnUnlock = false
    private var unlockReceiver: BroadcastReceiver? = null

    private fun isPhoneLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    } catch (e: Exception) { false }

    private fun killBackgroundApps() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (a in installedApps()) {
                if (a.pkg != packageName) {
                    try { am.killBackgroundProcesses(a.pkg) } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
    }

    // When the phone is unlocked after a "close all" asked on the lock screen, finish clearing the recent list
    private fun registerUnlockWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (!clearOnUnlock) return
                    clearOnUnlock = false
                    io.execute {
                        Thread.sleep(1200)
                        val ok = RiyaAccessibility.instance?.clearRecents() ?: false
                        handler.post { if (running) say(if (ok) "Recent apps cleared." else "I could not clear the recent list.", true) }
                    }
                }
            }
            val f = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
            unlockReceiver = r
        } catch (e: Exception) { }
    }

    private fun closeAllApps() {
        val acc = RiyaAccessibility.instance
        if (isPhoneLocked()) {
            // Android does not let any app touch the recent-apps screen while locked, so stop the apps now
            // and clear the list the moment the phone is unlocked.
            io.execute { killBackgroundApps() }
            if (acc != null) clearOnUnlock = true
            say(if (acc != null) "The phone is locked, so I stopped the background apps. I will clear the recent list when you unlock."
                else "The phone is locked, so I stopped the background apps. Turn on Riya in Accessibility to clear the recent list too.", true)
            return
        }
        say("Closing all apps.")
        io.execute {
            killBackgroundApps()
            val cleared = if (acc != null) acc.clearRecents() else false
            handler.post {
                if (!running) return@post
                val msg = when {
                    cleared -> "All apps are closed."
                    acc == null -> "I stopped the background apps. Turn on Riya in Accessibility to clear the recent list too."
                    else -> "I stopped the background apps, but I could not clear the recent list on this phone."
                }
                say(msg, true)
            }
        }
    }

    private fun setAlarm(c: String) {
        val nums = parseNums(c)
        if (nums.isEmpty()) { say("Tell me the time, for example set alarm for seven thirty a m", true); return }
        var h = nums[0]
        var m = 0
        if (nums.size > 2 && nums[1] == 0) m = nums[2]
        else if (nums.size > 1) m = nums[1]
        val pm = Regex("\\b(p m|pm|evening|night|afternoon)\\b").containsMatchIn(c)
        val am = Regex("\\b(a m|am|morning)\\b").containsMatchIn(c)
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h > 23 || m > 59) { say("I did not get that time. Please say it again.", true); return }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (launch(i)) {
            val shown = SimpleDateFormat("h:mm a", Locale.getDefault()).format(
                java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m) }.time)
            say("Alarm set for $shown", true)
        } else say("I could not find a clock app", true)
    }

    private fun setTimer(c: String) {
        val nums = parseNums(c)
        var secs = -1
        if ("half an hour" in c || "half hour" in c) secs = 1800
        else {
            val n = nums.firstOrNull() ?: if (Regex("\\b(an|a|one) (hour|minute)\\b").containsMatchIn(c)) 1 else null
            if (n != null) {
                val unit = when {
                    "hour" in c -> 3600
                    "second" in c -> 1
                    else -> 60
                }
                secs = n * unit
            }
        }
        if (secs <= 0) { say("For how long? Say for example set a timer for five minutes", true); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, assistantName)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (launch(i)) {
            val spoken = if (secs % 3600 == 0) "${secs / 3600} hour" else if (secs % 60 == 0) "${secs / 60} minute" else "$secs second"
            say("Timer set for $spoken", true)
        } else say("I could not find a clock app", true)
    }

    private fun openSettingsFor(c: String) {
        val i = when {
            "bluetooth" in c -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "brightness" in c -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "airplane" in c || "flight" in c -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "location" in c || "gps" in c -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "hotspot" in c -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        if (launch(i)) say("Opening it. Android needs you to tap the switch yourself.", true)
        else say("I could not open that setting", true)
    }

    private fun playLocal(song: String) {
        say("No internet, so I will play music stored on your phone.")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            val j = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            if (!launch(j)) say("I could not find a music player on this phone", true)
        }
    }

    // ---------- Volume ----------

    private fun volume(c: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stream = AudioManager.STREAM_MUSIC
        val percent = parseNums(c).firstOrNull()
        when {
            "unmute" in c -> am.setStreamVolume(stream, max / 2, AudioManager.FLAG_SHOW_UI)
            "mute" in c -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            percent != null -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "max" in c || "full" in c -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            "up" in c || "increase" in c || "raise" in c || "louder" in c || "high" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            "down" in c || "decrease" in c || "lower" in c || "low" in c || "reduce" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            else -> { say("Say volume up, down, mute, or a number"); return }
        }
        say("Volume " + (am.getStreamVolume(stream) * 100 / max) + " percent")
    }

    // ---------- Other actions ----------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        } else {
            val acc = RiyaAccessibility.instance
            if (acc != null && Build.VERSION.SDK_INT >= 28) acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            else say("Enable Device admin in the Riya app first")
        }
    }

    private fun openCamera() {
        if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) say("Camera not found")
        }
    }

    private fun youtube(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openSpotify() {
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i == null || !launch(i)) say("Spotify is not installed")
    }

    private fun playSpotify(song: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) {
                say("Spotify is not installed")
            }
        }
    }

    override fun onDestroy() {
        running = false
        state = "stopped"
        handler.removeCallbacksAndMessages(null)
        listener?.invoke()
        io.shutdownNow()
        try { overlayView?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (e: Exception) {}
        try { callReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        try { unlockReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        ears?.stop()
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        unmuteBeeps()
        Thread { localLlm?.close() }.start()
        super.onDestroy()
    }
}
