package dev.warden.apps

import dev.warden.design.WardenButton
import dev.warden.design.ButtonKind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.warden.design.SegmentedControl
import dev.warden.design.Spacing
import dev.warden.design.Warden
import dev.warden.engine.BuiltInTor
import dev.warden.engine.Engine
import dev.warden.engine.TorState
import dev.warden.rules.Route
import dev.warden.rules.Routing
import dev.warden.rules.Dns
import dev.warden.rules.Upstream
import dev.warden.design.R as DesignR

/** Where this app's traffic goes: direct, through Tor (built in), or through the user's WireGuard server. */
@Composable
internal fun RouteRow(uid: Int, name: String) {
    val c = Warden.colors
    val routes by Routing.routes.collectAsState()
    val wireGuard by Routing.wireGuard.collectAsState()
    val servers by Routing.servers.collectAsState()
    val appServers by Routing.appServer.collectAsState()
    val selected by Routing.selected.collectAsState()
    val route = routes[uid] ?: Route.Direct
    val pinnedServerId = appServers[uid]

    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(DesignR.drawable.ph_lock_key_light),
            null,
            Modifier.size(20.dp),
            tint = if (route == Route.Direct) c.text else c.accentInk,
        )
        Text(stringResource(R.string.route_title), Modifier.weight(1f).padding(start = Spacing.m), style = Warden.type.body)
    }
    SegmentedControl(
        listOf(stringResource(R.string.route_direct), stringResource(R.string.route_tor), stringResource(R.string.route_vpn)),
        route.ordinal,
        { Routing.set(uid, Route.entries[it]) },
    )
    when (route) {
        Route.Direct -> if (Routing.vpnForAll.collectAsState().value && wireGuard != null) {
            Note(stringResource(R.string.route_vpn_all), warn = false)
        }
        Route.Tor -> TorStatus(name)
        Route.Vpn -> {
            if (servers.isEmpty()) {
                Note(stringResource(R.string.route_vpn_missing, name), warn = true)
            } else {
                val effectiveId = pinnedServerId ?: selected
                val server = servers.firstOrNull { it.id == effectiveId } ?: servers.first()
                Note(stringResource(R.string.route_vpn_on, server.name.ifEmpty { server.config.endpoint }), warn = false)
                // Per-app server picker when multiple profiles exist
                if (servers.size > 1) {
                    val labels = listOf(stringResource(R.string.route_vpn_default)) + servers.map { it.name.ifEmpty { it.config.endpoint } }
                    val idx = if (pinnedServerId == null) 0 else (servers.indexOfFirst { it.id == pinnedServerId }.takeIf { it >= 0 }?.plus(1) ?: 0)
                    SegmentedControl(
                        labels,
                        idx.coerceIn(0, labels.lastIndex),
                        { i ->
                            if (i == 0) Routing.setAppServer(uid, null)
                            else Routing.setAppServer(uid, servers[i - 1].id)
                        },
                    )
                }
            }
        }
    }
}

/** Tor runs inside Warden; until it has connected, this app stays offline rather than going direct. */
@Composable
private fun TorStatus(name: String) {
    val state by BuiltInTor.state.collectAsState()
    val running by Engine.running.collectAsState()
    val context = LocalContext.current
    when {
        !running -> Note(stringResource(R.string.tor_when_on), warn = false)
        state == TorState.On -> Note(stringResource(R.string.tor_on), warn = false)
        state == TorState.Failed -> {
            Note(stringResource(R.string.tor_failed, name), warn = true)
            WardenButton(
                stringResource(R.string.tor_retry),
                { BuiltInTor.retry(context) },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Tonal,
            )
        }
        else -> Note(stringResource(R.string.tor_connecting, name), warn = true)
    }
}

@Composable
private fun Note(text: String, warn: Boolean) {
    Text(text, style = Warden.type.label, color = if (warn) Warden.colors.warning else Warden.colors.muted)
}

/** Per-app DNS override. Default = global setting from Settings. */
@Composable
internal fun AppDnsRow(uid: Int) {
    val c = Warden.colors
    val overrides by Dns.appUpstream.collectAsState()
    val current = overrides[uid]
    val options = listOf(null to stringResource(R.string.app_dns_global)) +
        listOf(
            Upstream.Cloudflare to "Cloudflare",
            Upstream.AdGuard to "AdGuard",
            Upstream.AdGuardFamily to "AdGuard Family",
            Upstream.Quad9 to "Quad9",
            Upstream.MullvadAdblock to "Mullvad Adblock",
            Upstream.CleanBrowsingFamily to "CleanBrowsing Family",
            Upstream.Google to "Google",
        )
    val selectedIdx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(DesignR.drawable.ph_globe_hemisphere_west),
            null,
            Modifier.size(20.dp),
            tint = if (current == null) c.text else c.accentInk,
        )
        Text(stringResource(R.string.app_dns_title), Modifier.weight(1f).padding(start = Spacing.m), style = Warden.type.body)
    }
    SegmentedControl(
        options.map { it.second },
        selectedIdx.coerceIn(0, options.lastIndex),
        { i -> Dns.setAppUpstream(uid, options[i].first) },
    )
}
