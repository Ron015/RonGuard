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
    var maxPending: Int = 20_000
        private set

    /** Live log / apps list window (newest first). */
    @Volatile
    var recentRows: Int = 1_000
        private set

    /** Map screen connection cap. */
    @Volatile
    var mapRows: Int = 5_000
        private set

    /** Max rows kept in Room after prune. */
    @Volatile
    var keepRows: Int = 200_000
        private set

    /** How many days of history to retain. */
    @Volatile
    var keepDays: Long = 30L
        private set

    /** Blocked-domains list on the blocklists screen. */
    @Volatile
    var blockedRows: Int = 100
        private set

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val am = context.applicationContext.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalMb = mem.totalMem / (1024L * 1024L)
        val classMb = am?.memoryClass ?: 64
        lowRam = (am?.isLowRamDevice == true) || totalMb in 1..3072 || classMb <= 128

        if (lowRam) {
            maxPending = 2_000
            recentRows = 250
            mapRows = 800
            keepRows = 25_000
            keepDays = 7L
            blockedRows = 40
        } else {
            maxPending = 20_000
            recentRows = 1_000
            mapRows = 5_000
            keepRows = 200_000
            keepDays = 30L
            blockedRows = 100
        }
    }
}
