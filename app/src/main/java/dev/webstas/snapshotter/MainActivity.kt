package dev.webstas.snapshotter

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tile(R.id.tile_processed).second.setText(R.string.stat_processed)
        tile(R.id.tile_changed).second.setText(R.string.stat_changed)
        tile(R.id.tile_skipped).second.setText(R.string.stat_skipped)
        tile(R.id.tile_elapsed).second.setText(R.string.stat_elapsed)

        findViewById<View>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.start_button).setOnClickListener { start() }
        findViewById<View>(R.id.stop_button).setOnClickListener {
            SnapshotService.instance?.stop()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLoop.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    /** A stat tile's (value, label) views. */
    private fun tile(id: Int): Pair<TextView, TextView> {
        val tile = findViewById<View>(id)
        return tile.findViewById<TextView>(R.id.stat_value) to tile.findViewById<TextView>(R.id.stat_label)
    }

    private fun refresh() {
        findViewById<TextView>(R.id.service_text).setText(
            if (serviceEnabled()) R.string.service_on else R.string.service_off,
        )
        val run = SnapshotService.stats
        val end = if (run.endedAt > 0) run.endedAt else System.currentTimeMillis()
        val seconds = if (run.startedAt > 0) (end - run.startedAt) / 1000 else 0
        findViewById<TextView>(R.id.run_status).text = run.status
        tile(R.id.tile_processed).first.text = run.processed.toString()
        tile(R.id.tile_changed).first.text = run.changed.toString()
        tile(R.id.tile_skipped).first.text = run.skipped.toString()
        tile(R.id.tile_elapsed).first.text = "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Read from settings rather than the live instance, which briefly disappears while Android restarts the service. */
    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains(ComponentName(this, SnapshotService::class.java).flattenToString())
    }

    private fun start() {
        val service = SnapshotService.instance
        val launch = packageManager.getLaunchIntentForPackage(SnapshotService.PKG)
        when {
            service == null -> Toast.makeText(this, R.string.enable_first, Toast.LENGTH_LONG).show()
            launch == null -> Toast.makeText(this, R.string.progressive_missing, Toast.LENGTH_LONG).show()
            else -> {
                service.start()
                startActivity(launch)
            }
        }
    }
}
