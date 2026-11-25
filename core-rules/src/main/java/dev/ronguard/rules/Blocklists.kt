package dev.ronguard.rules

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A downloadable list. Nothing is bundled: lists come from their publishers when switched on. */
data class BlocklistSource(val id: String, val name: String, val url: String, val license: String, val custom: Boolean = false)

data class BlocklistState(
    val enabled: Boolean = false,
    val entries: Int = 0,
    val updatedAt: Long = 0,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Downloads, stores and matches blocklists. Shared by the VPN service (matching) and the UI
 * (switches). Both live in one process, so this is a plain object; [init] is idempotent.
 */
object Blocklists {
    val catalog = listOf(
        BlocklistSource(
            "hagezi-light", "HaGeZi Light",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/light-onlydomains.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "hagezi-normal", "HaGeZi Multi Normal",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/multi-onlydomains.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "hagezi-pro", "HaGeZi Multi Pro",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/pro-onlydomains.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "hagezi-tif", "HaGeZi Threat Intelligence",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/tif-onlydomains.txt", "GPL-3.0",
        ),
        BlocklistSource("stevenblack", "StevenBlack Unified", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "MIT"),
        BlocklistSource(
            "stevenblack-fakenews-gambling-porn-social", "StevenBlack + social/gambling",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-gambling-porn-social/hosts", "MIT",
        ),
        BlocklistSource("oisd-small", "OISD Small", "https://small.oisd.nl/domainswild", "GPL-3.0"),
        BlocklistSource("oisd-big", "OISD Big", "https://big.oisd.nl/domainswild", "GPL-3.0"),
        BlocklistSource(
            "adguard-dns-filter", "AdGuard DNS filter",
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "adguard-mobile-ads", "AdGuard Mobile Ads",
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_11.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "easyprivacy", "EasyPrivacy",
            "https://easylist.to/easylist/easyprivacy.txt", "GPL-3.0",
        ),
        BlocklistSource(
            "1hosts-lite", "1Hosts (Lite)",
            "https://o0.pages.dev/Lite/domains.txt", "MPL-2.0",
        ),
    )

    /** Named profiles: enable a sensible set of lists in one tap. */
    data class Profile(val id: String, val name: String, val listIds: List<String>)

    val profiles = listOf(
        Profile("ads", "Ads & trackers", listOf("hagezi-light", "adguard-dns-filter", "1hosts-lite")),
        Profile("standard", "Standard protection", listOf("hagezi-normal", "oisd-small", "stevenblack")),
        Profile("max", "Maximum", listOf("hagezi-pro", "hagezi-tif", "oisd-big", "easyprivacy", "adguard-mobile-ads")),
        Profile("family", "Family / focus", listOf("stevenblack-fakenews-gambling-porn-social", "hagezi-normal")),
        Profile("off", "All off", emptyList()),
    )

    fun applyProfile(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        val want = profile.listIds.toSet()
        _sources.value.forEach { src ->
            setEnabled(src.id, src.id in want)
        }
    }

    private val _sources = MutableStateFlow(catalog)

    /** The built-in lists, then any the user added by URL. */
    val sources: StateFlow<List<BlocklistSource>> = _sources.asStateFlow()

    private val _states = MutableStateFlow(catalog.associate { it.id to BlocklistState() })
    val states: StateFlow<Map<String, BlocklistState>> = _states.asStateFlow()

    @Volatile private var matcher = DomainSet(emptySet())
    private val started = AtomicBoolean()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private lateinit var cm: ConnectivityManager

    /** True when [domain] (or a parent of it) is on an enabled list. Safe from any thread. */
    fun isBlocked(domain: String): Boolean = matcher.matches(domain)

    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        dir = File(app.filesDir, "blocklists").apply { mkdirs() }
        prefs = app.getSharedPreferences("blocklists", Context.MODE_PRIVATE)
        cm = app.getSystemService(ConnectivityManager::class.java)
        _sources.value = catalog + prefs.all.keys.filter { it.startsWith(CUSTOM_URL) }.sorted().mapNotNull { key ->
            val id = key.removePrefix(CUSTOM_URL)
            val url = prefs.getString(key, null) ?: return@mapNotNull null
            BlocklistSource(id, prefs.getString(CUSTOM_NAME + id, null).orEmpty().ifEmpty { hostOf(url) }, url, hostOf(url), custom = true)
        }
        _states.value = _sources.value.associate {
            it.id to BlocklistState(
                enabled = prefs.getBoolean("on.${it.id}", false),
                entries = prefs.getInt("n.${it.id}", 0),
                updatedAt = prefs.getLong("t.${it.id}", 0),
            )
        }
        scope.launch {
            rebuild()
            refreshStale()
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        prefs.edit().putBoolean("on.$id", on).apply()
        set(id) { it.copy(enabled = on, failed = false) }
        scope.launch {
            if (on && !file(id).exists()) download(id)
            rebuild()
        }
    }

    /**
     * Adds a list from any https URL (hosts file or one domain per line), switches it on and downloads it.
     * Throws [IllegalArgumentException] if the URL can't be used.
     */
    fun addCustom(name: String, url: String) {
        val clean = url.trim()
        ensure(isValidListUrl(clean), Problem.NotHttps)
        ensure(_sources.value.none { it.url == clean }, Problem.AlreadyAdded)
        val id = "custom-" + System.currentTimeMillis()
        val label = name.trim().ifEmpty { hostOf(clean) }
        prefs.edit().putString(CUSTOM_URL + id, clean).putString(CUSTOM_NAME + id, name.trim()).apply()
        _sources.update { it + BlocklistSource(id, label, clean, hostOf(clean), custom = true) }
        _states.update { it + (id to BlocklistState()) }
        setEnabled(id, true)
    }

    fun removeCustom(id: String) {
        prefs.edit().remove(CUSTOM_URL + id).remove(CUSTOM_NAME + id)
            .remove("on.$id").remove("n.$id").remove("t.$id").apply()
        _sources.update { list -> list.filterNot { it.id == id } }
        _states.update { it - id }
        scope.launch {
            lock.withLock { file(id).delete(); cleaned(id).delete() }
            rebuild()
        }
    }

    fun refresh(id: String) {
        scope.launch {
            download(id)
            rebuild()
        }
    }

    private fun file(id: String) = File(dir, "$id.txt")

    // A list deleted while it downloads simply drops the late update.
    private fun set(id: String, f: (BlocklistState) -> BlocklistState) =
        _states.update { states -> states[id]?.let { states + (id to f(it)) } ?: states }

    private suspend fun download(id: String) {
        val source = _sources.value.firstOrNull { it.id == id } ?: return
        set(id) { it.copy(busy = true, failed = false) }
        try {
            val raw = File(dir, "$id.raw.tmp")
            val cleanedTmp = File(dir, "$id.domains.tmp")
            fetch(source.url, raw)
            // Stream-parse off the UI path; yield often so large lists do not freeze the process.
            val domains = HashSet<String>(65_536)
            val entries = parseFile(raw, domains)
            raw.delete()
            if (entries == 0) throw IOException("no domains in ${source.url}")
            writeDomains(cleanedTmp, domains)
            if (!swapCleanedFile(id, cleanedTmp)) return
            val now = System.currentTimeMillis()
            prefs.edit().putInt("n.$id", entries).putLong("t.$id", now).apply()
            set(id) { it.copy(entries = entries, updatedAt = now, busy = false) }
        } catch (e: IOException) {
            Log.w(TAG, "download $id failed", e)
            set(id) { it.copy(busy = false, failed = true) }
        }
    }

    /** @return false if the source was removed during download. */
    private suspend fun swapCleanedFile(id: String, cleanedTmp: File): Boolean = lock.withLock {
        if (_sources.value.none { it.id == id }) {
            cleanedTmp.delete()
            return@withLock false
        }
        file(id).delete()
        cleaned(id).delete()
        cleanedTmp.renameTo(cleaned(id)) || throw IOException("could not save $id")
        true
    }

    private fun fetch(url: String, to: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "RonGuard")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /** Re-reads every enabled list into one matcher; swapped in atomically when ready. */
    private suspend fun rebuild() = lock.withLock {
        val all = HashSet<String>(65_536)
        val ids = _states.value.filterValues { it.enabled }.keys.toList()
        for (id in ids) {
            val f = cleaned(id).takeIf { it.exists() } ?: file(id).takeIf { it.exists() }
            if (f != null) parseFile(f, all)
        }
        matcher = DomainSet(all)
        Rules.reset("") // lists affect every app
    }

    private suspend fun writeDomains(to: File, domains: Set<String>) {
        val out = to.bufferedWriter()
        try {
            var n = 0
            for (d in domains) {
                out.append(d).append('\n')
                n++
                if (n % YIELD_EVERY == 0) kotlinx.coroutines.yield()
            }
        } finally {
            out.close()
        }
    }

    /** Parse a list file cooperatively (yields so large lists do not stall the process). */
    private suspend fun parseFile(file: File, into: MutableSet<String>): Int {
        val reader = file.bufferedReader()
        try {
            return consumeLines(reader, into)
        } finally {
            reader.close()
        }
    }

    private suspend fun consumeLines(reader: java.io.BufferedReader, into: MutableSet<String>): Int {
        var seen = 0
        var added = 0
        var line = reader.readLine()
        while (line != null) {
            if (BlocklistParser.domainOf(line)?.let(into::add) == true) added++
            seen++
            if (seen % YIELD_EVERY == 0) kotlinx.coroutines.yield()
            line = reader.readLine()
        }
        return added
    }

    private fun cleaned(id: String) = File(dir, "$id.domains")

    /** ponytail: weekly refresh checked on start, unmetered networks only; WorkManager scheduling comes later. */
    private suspend fun refreshStale() {
        if (cm.isActiveNetworkMetered) return
        val stale = _states.value.filter { (_, s) ->
            s.enabled && System.currentTimeMillis() - s.updatedAt > TimeUnit.DAYS.toMillis(STALE_DAYS)
        }
        if (stale.isEmpty()) return
        stale.keys.forEach { download(it) }
        rebuild()
    }

    private fun hostOf(url: String) = runCatching { URI(url).host }.getOrNull().orEmpty().ifEmpty { url }

    private const val CUSTOM_URL = "custom.url."
    private const val CUSTOM_NAME = "custom.name."
    private const val TAG = "Blocklists"
    private const val TIMEOUT_MS = 30_000
    private const val STALE_DAYS = 7L
    private const val YIELD_EVERY = 4_000
}

/** A list address Warden will fetch: https with a host. Plain http could be tampered with on the way. */
fun isValidListUrl(url: String): Boolean = runCatching {
    val u = URI(url.trim())
    u.scheme == "https" && !u.host.isNullOrBlank()
}.getOrDefault(false)
