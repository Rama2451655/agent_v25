package com.rama.riya

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 1) Presses WhatsApp's Send button, only for ~20 seconds after Riya opens a prefilled chat.
 * 2) Works offline: home / back / recents / notifications / screenshot, and "close all apps".
 */
class RiyaAccessibility : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        @Volatile var instance: RiyaAccessibility? = null
        fun arm() { armedUntil = System.currentTimeMillis() + 20000 }
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    override fun onDestroy() { instance = null; worker.shutdownNow(); super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) { armedUntil = 0; return }
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        if (byText.isNotEmpty() && click(byText[0])) armedUntil = 0
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    // ---------- offline phone controls ----------

    /** Opens the recent-apps screen and clears it. Blocks, so call it from a background thread. */
    fun clearRecents(): Boolean {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Thread.sleep(1600)
        var cleared = false
        // 1) find the "clear all" button (text, description or id), scrolling the carousel if needed
        for (attempt in 0 until 3) {
            if (pressClearAll()) { cleared = true; break }
            swipeRight()
            Thread.sleep(600)
        }
        // 2) works on every phone: if there is no button (Realme, Oppo, Samsung ...), swipe each card up
        if (!cleared) {
            var any = false
            for (i in 0 until 14) {
                if (swipeUpCard()) any = true
                Thread.sleep(380)
            }
            cleared = any
        }
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return cleared
    }

    private val buttonWords = listOf(
        "clear all", "close all", "clear_all", "clearall", "close_all", "closeall", "delete all", "delete_all",
        "dismiss all", "dismiss_all", "remove all", "remove_all", "clear-all", "clear_recent", "clear all apps",
        "clean_all", "trash", "clear_button", "clear_btn"
    )

    private fun isClearButton(n: AccessibilityNodeInfo): Boolean {
        val id = (n.viewIdResourceName ?: "").lowercase()
        val txt = (n.text?.toString() ?: "").lowercase()
        val desc = (n.contentDescription?.toString() ?: "").lowercase()
        return buttonWords.any { id.contains(it) || txt.contains(it) || desc.contains(it) }
    }

    private var visited = 0

    private fun findClear(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 30 || visited > 3000) return null
        visited++
        if (isClearButton(n)) return n
        for (i in 0 until n.childCount) {
            val r = findClear(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun pressClearAll(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try { for (w in windows) { w.root?.let { roots.add(it) } } } catch (e: Exception) { }
        for (root in roots) {
            visited = 0
            val hit = findClear(root, 0) ?: continue
            if (click(hit)) return true
            if (tapNode(hit)) return true      // button not "clickable" for accessibility: tap its position
        }
        return false
    }

    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val p = Path()
        p.moveTo(r.exactCenterX(), r.exactCenterY())
        return gesture(GestureDescription.StrokeDescription(p, 0, 60))
    }

    private fun gesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var done = false
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (ok) latch.await(1500, TimeUnit.MILLISECONDS)
        return ok && done
    }

    private fun swipeRight() {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.45f
        val p = Path()
        p.moveTo(dm.widthPixels * 0.2f, y)
        p.lineTo(dm.widthPixels * 0.85f, y)
        gesture(GestureDescription.StrokeDescription(p, 0, 250))
    }

    /** Flicks the centre recent-app card upwards (that closes it). */
    private fun swipeUpCard(): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val p = Path()
        p.moveTo(x, dm.heightPixels * 0.62f)
        p.lineTo(x, dm.heightPixels * 0.10f)
        return gesture(GestureDescription.StrokeDescription(p, 0, 160))
    }

    override fun onInterrupt() {}
}
