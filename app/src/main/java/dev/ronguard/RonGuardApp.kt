package dev.ronguard

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import dev.ronguard.data.CrashLog
import dev.ronguard.data.History
import dev.ronguard.engine.DeviceMem
import dev.ronguard.engine.Engine
import dev.ronguard.rules.Rules
import dev.ronguard.settings.UpdateCheck
import java.io.File

/** Process-wide startup: rules and the history writer run whether the UI or only the VPN is alive. */
class RonGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Built-in Tor runs in its own ":tor" process; it needs none of this (and two history writers would clash).
        if (processName().endsWith(":tor")) return
        DeviceMem.init(this)
        CrashLog.install(this)
        Rules.init(this)
        History.init(this)
        UpdateCheck.ensureScheduled(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Drop live queues when Android is under pressure so we are less likely to be killed.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            Engine.trimMemory()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Engine.clear()
        }
    }

    private fun processName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
    } else {
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
    }
}
