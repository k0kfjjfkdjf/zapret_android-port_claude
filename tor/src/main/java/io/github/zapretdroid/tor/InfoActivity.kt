package io.github.zapretdroid.tor

import IPtProxy.IPtProxy
import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.torproject.jni.TorService

/** The launcher entry: what this module is, its versions and Tor's state; settings live in ByeZapret. */
class InfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val status = "${TorModuleService.state} ${TorModuleService.progress}% ${TorModuleService.message}".trim()
        val text = TextView(this).apply {
            text = getString(
                R.string.info, TorService.VERSION_NAME,
                runCatching { IPtProxy.lyrebirdVersion() }.getOrDefault("?"),
                runCatching { IPtProxy.snowflakeVersion() }.getOrDefault("?"),
                status,
            )
            textSize = 16f
        }
        // The app of the same build type: io.github.zapretdroid(.debug).
        val appPackage = packageName.replace(".tor", "")
        val open = Button(this).apply {
            text = getString(R.string.open_app)
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(appPackage)?.let { startActivity(it) }
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(text)
            addView(open)
        })
    }
}
