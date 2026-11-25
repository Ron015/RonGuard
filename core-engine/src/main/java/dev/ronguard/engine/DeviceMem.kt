package dev.ronguard.engine

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory budget for history and live queues. Low-RAM phones (≤3 GB, or [ActivityManager.isLowRamDevice])
 * keep fewer rows in memory and on disk so Android is less likely to kill the process under pressure.
 */
object DeviceMem {
    private val started = AtomicBoolean()

    /** True on go/low-RAM devices or when the system reports a small heap / ≤3 GB total RAM. */
    @Volatile
    var lowRam: Boolean = false
        private set

    /** Max NetEvents held in the engine until History drains them. */
    @Volatile
    var maxPending: Int = 12_000
        private set

    /** Max rows the log can page through (newest first). */
    @Volatile
    var recentRows: Int = 800
        private set

    /** Shared live list for Apps tab (keep small — full list recomposes often). */
    @Volatile
    var appsRows: Int = 120
        private set

    /** How many log rows to show before the user scrolls for more. */
    @Volatile
    var logPageSize: Int = 50
        private set

    /** Map screen connection cap. */
    @Volatile
    var mapRows: Int = 3_000
        private set

    /** Max rows kept in Room after prune. */
    @Volatile
    var keepRows: Int = 100_000
        private set

    /** How many days of history to retain. */
    @Volatile
    var keepDays: Long = 21L
        private set

    /** Blocked-domains list on the blocklists screen. */
    @Volatile
    var blockedRows: Int = 80
        private set

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val am = context.applicationContext.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalMb = mem.totalMem / (1024L * 1024L)
        val classMb = am?.memoryClass ?: 64
        lowRam = (am?.isLowRamDevice == true) || totalMb in 1..3072 || classMb <= 128

        if (lowRam) {
            maxPending = 1_500
            recentRows = 200
            appsRows = 60
            logPageSize = 40
            mapRows = 400
            keepRows = 50_000
            keepDays = 14L
            blockedRows = 30
        } else {
            maxPending = 12_000
            recentRows = 800
            appsRows = 120
            logPageSize = 60
            mapRows = 3_000
            keepRows = 250_000
            keepDays = 45L
            blockedRows = 80
        }
    }
}
