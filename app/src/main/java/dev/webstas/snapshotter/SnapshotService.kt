package dev.webstas.snapshotter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.material.color.DynamicColors

/**
 * Walks the Progressive app to Snapshot > Trips, and for each trip with events > 0 opens the
 * transit mode picker. Trips detected as a drive are set to Other and saved; for any other trip the
 * user picks the mode.
 */
class SnapshotService : AccessibilityService() {

    private enum class Phase { SNAPSHOT, TRIPS, TOP, SCAN, WAIT_USER }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (running) step()
            handler.postDelayed(this, STEP_MS)
        }
    }

    private var running = false
    private var phase = Phase.SNAPSHOT
    private val reviewed = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private var pickerSeen = false
    private var otherPicked = false
    private var pending: Row? = null
    private var overlay: View? = null
    private var idleTicks = 0

    override fun onServiceConnected() {
        instance = this
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        // A rebound service can connect before the old one is destroyed; only clear our own reference.
        if (instance === this) instance = null
        hideOverlay()
        super.onDestroy()
    }

    fun start() {
        reviewed.clear()
        skipped.clear()
        pending = null
        stats = RunStats(startedAt = System.currentTimeMillis(), status = "Running")
        setPhase(Phase.SNAPSHOT)
        running = true
        showOverlay()
    }

    fun stop() = end("Stopped")

    private fun end(status: String) {
        running = false
        stats.status = status
        stats.endedAt = System.currentTimeMillis()
        hideOverlay()
    }

    private fun finish(message: String) {
        end(message)
        openMainScreen()
    }

    private fun openMainScreen() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Floating Stop / Return controls, drawn over every app while a review runs. */
    private fun showOverlay() {
        if (overlay != null) return
        val themed = DynamicColors.wrapContextIfAvailable(this, R.style.Theme_SnapshotReviewer)
        val bar = LayoutInflater.from(themed).inflate(R.layout.overlay_controls, null)
        bar.findViewById<View>(R.id.overlay_stop).setOnClickListener { stop() }
        bar.findViewById<View>(R.id.overlay_return).setOnClickListener { openMainScreen() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = OVERLAY_TOP_PX
        }
        getSystemService(WindowManager::class.java).addView(bar, params)
        overlay = bar
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    private fun setPhase(next: Phase) {
        phase = next
        idleTicks = 0
        Log.d(TAG, "phase=$next")
    }

    private fun step() {
        val root = rootInActiveWindow ?: return
        if (root.packageName != PKG) return
        idleTicks++

        when (phase) {
            Phase.SNAPSHOT -> {
                when {
                    root.find(TRIPS_SCREEN) != null -> setPhase(Phase.TOP)
                    root.find(TRIPS_TAB) != null -> setPhase(Phase.TRIPS)
                    else -> root.find(SNAPSHOT_LABEL)?.click()
                }
                if (idleTicks > GIVE_UP_TICKS) finish("Could not find Snapshot in the app")
            }

            Phase.TRIPS -> {
                if (root.find(TRIPS_SCREEN) != null) return setPhase(Phase.TOP)
                root.find(TRIPS_TAB)?.click()
                if (idleTicks > GIVE_UP_TICKS) finish("Could not find Trips")
            }

            // The list keeps its scroll position between runs; start from the newest trip.
            Phase.TOP -> if (!scrollTripList(root, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) && idleTicks > SCAN_SETTLE_TICKS) {
                setPhase(Phase.SCAN)
            }

            Phase.SCAN -> {
                countChange(root)
                val trips = tripRows(root)
                skipped += trips.filter { it.events == 0 }.map { it.key }
                stats.skipped = skipped.size
                val next = trips.firstOrNull { it.events > 0 && it.key !in reviewed }
                if (next != null) {
                    reviewed += next.key
                    pending = next
                    stats.processed++
                    stats.status = "Waiting for you: trip${next.key.trim().trimEnd('.')}"
                    pickerSeen = false
                    otherPicked = false
                    next.edit.click()
                    setPhase(Phase.WAIT_USER)
                } else if (!scrollTripList(root, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) && idleTicks > SCAN_SETTLE_TICKS) {
                    finish("Done: reviewed ${reviewed.size} trips with events")
                }
            }

            Phase.WAIT_USER -> {
                // The user saves or cancels the picker; once it closes, move to the next trip.
                if (root.find(PICKER_TITLE) != null) {
                    pickerSeen = true
                    idleTicks = 0
                    if (pending?.mode == DRIVER) reclassifyDrive(root)
                } else if (pickerSeen) setPhase(Phase.SCAN)
                else if (idleTicks > GIVE_UP_TICKS) finish("Transit mode picker never opened")
            }
        }
    }

    /** Drive found: select Other on one tick, then Save transit mode on the next. */
    private fun reclassifyDrive(root: AccessibilityNodeInfo) {
        if (!otherPicked) {
            root.find(OTHER_OPTION)?.click()?.also { otherPicked = true }
        } else root.find(SAVE_BUTTON)?.click()
    }

    /** [mode] is what the trip was set to when opened, so a changed mode can be counted afterwards. */
    private class Row(val edit: AccessibilityNodeInfo, val key: String, val mode: String, val events: Int)

    /** Once the picker closes, compares the opened trip's mode with what it was before. */
    private fun countChange(root: AccessibilityNodeInfo) {
        val opened = pending ?: return
        pending = null
        val now = root.findDesc(EDIT_LABEL, opened.key) ?: return
        if (now.label().substringBefore(",") != opened.mode) stats.changed++
    }

    /**
     * Trips that show an "N events" count (trips already marked Passenger show none). The row's edit-transit-mode control is the node whose
     * description ends in "edit transit mode."; its description names the trip start, so it is the key.
     */
    private fun tripRows(root: AccessibilityNodeInfo): List<Row> {
        val rows = mutableListOf<Row>()
        root.walk { node ->
            val count = EVENTS.find(node.label())?.groupValues?.get(1)?.toIntOrNull() ?: return@walk
            var row = node.parent
            while (row != null && row.findDesc(EDIT_LABEL) == null) row = row.parent
            val edit = row?.findDesc(EDIT_LABEL) ?: return@walk
            // Drop the leading mode ("Driver, Auto, ") so changing the mode doesn't change the key.
            rows += Row(edit, edit.label().substringAfter("transit mode for trip started"), edit.label().substringBefore(","), count)
        }
        return rows
    }

    private fun AccessibilityNodeInfo.findDesc(pattern: Regex, contains: String = ""): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk { if (hit == null && pattern.containsMatchIn(it.label()) && contains in it.label()) hit = it }
        return hit
    }

    private fun scrollTripList(root: AccessibilityNodeInfo, direction: Int): Boolean {
        var scrolled = false
        root.walk { node ->
            if (!scrolled && node.isScrollable) {
                scrolled = node.performAction(direction)
            }
        }
        if (scrolled) idleTicks = 0
        return scrolled
    }

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString().orEmpty()

    private fun AccessibilityNodeInfo.walk(visit: (AccessibilityNodeInfo) -> Unit) {
        visit(this)
        for (i in 0 until childCount) getChild(i)?.walk(visit)
    }

    private fun AccessibilityNodeInfo.find(pattern: Regex): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk { if (hit == null && pattern.containsMatchIn(it.label())) hit = it }
        return hit
    }

    /** Clicks the nearest clickable ancestor; falls back to a screen tap when none exists. */
    private fun AccessibilityNodeInfo.click(): Unit? {
        var n: AccessibilityNodeInfo? = this
        while (n != null && !n.isClickable) n = n.parent
        if (n != null) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return Unit
        }
        val r = Rect().also(::getBoundsInScreen)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
        return Unit
    }

    companion object {
        const val PKG = "com.phonevalley.progressive"
        private const val TAG = "SnapshotReviewer"
        private const val STEP_MS = 1000L
        private const val GIVE_UP_TICKS = 20
        private const val SCAN_SETTLE_TICKS = 3
        private const val OVERLAY_TOP_PX = 200

        // Screen labels in the Progressive app. Adjust here if the app's wording differs.
        private val SNAPSHOT_LABEL = Regex("(?i)^snapshot\\s*®?$")
        private val TRIPS_TAB = Regex("(?i)^trips$")
        private val TRIPS_SCREEN = Regex("(?i)^your snapshot.{0,3} trips$")
        private val EDIT_LABEL = Regex("edit transit mode\\.?$")
        private val PICKER_TITLE = Regex("^Which transit mode did you use")
        private val OTHER_OPTION = Regex("(?i)^other$")
        private val SAVE_BUTTON = Regex("(?i)^save transit mode$")
        private const val DRIVER = "Driver"
        private val EVENTS = Regex("(?i)(\\d+)\\s+events?")

        var instance: SnapshotService? = null
            private set

        /** Progress of the latest run, read by the main screen. */
        var stats = RunStats()
            private set
    }
}

class RunStats(
    var processed: Int = 0,
    var changed: Int = 0,
    var skipped: Int = 0,
    val startedAt: Long = 0,
    var endedAt: Long = 0,
    var status: String = "Not started",
)
