package dev.warden.settings

import androidx.compose.runtime.key
import dev.warden.rules.Verdict
import dev.warden.design.ChoiceChips
import androidx.compose.animation.animateContentSize
import dev.warden.rules.Rules
import dev.warden.design.Radii
import androidx.compose.ui.draw.clip
import java.util.Date
import java.text.NumberFormat
import java.text.DateFormat
import dev.warden.design.tabular
import dev.warden.design.SegmentedControl
import dev.warden.data.Range
import dev.warden.data.History
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.warden.design.BarSpacer
import dev.warden.design.FilterChip
import dev.warden.design.SectionLabel
import dev.warden.design.Spacing
import dev.warden.design.Tile
import dev.warden.design.Warden
import dev.warden.rules.BlocklistState
import dev.warden.rules.Blocklists
import dev.warden.design.R as DesignR

/** Blocklists on their own screen, opened from Home's tools: built-in lists, then ones you added. */
@Composable
fun BlocklistsScreen(backLabel: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c = Warden.colors
    val sources by Blocklists.sources.collectAsState()
    val states by Blocklists.states.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(painterResource(DesignR.drawable.ph_arrow_left), stringResource(R.string.vpn_back), Modifier.size(20.dp), tint = c.text)
            SectionLabel(backLabel)
        }
        Text(stringResource(R.string.settings_blocklists), style = Warden.type.headline)
        Text(stringResource(R.string.settings_blocklists_note), style = Warden.type.body, color = c.muted)
        // Quick-toggle profiles
        Text(stringResource(R.string.bl_profiles_title), style = Warden.type.body, modifier = Modifier.padding(top = Spacing.m))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Blocklists.profiles.forEach { profile ->
                FilterChip(
                    profile.name,
                    false,
                    { Blocklists.applyProfile(profile.id) },
                )
            }
        }
        Tile(Modifier.fillMaxWidth().padding(top = Spacing.s)) {
            sources.forEachIndexed { i, source ->
                if (i > 0) HorizontalDivider(color = c.outline)
                BlocklistRow(source, states[source.id] ?: BlocklistState())
            }
            AddListButton()
        }
        CustomDomainBlock()
        BlockedDomains()
        BarSpacer()
    }
}

/** Type a domain or subdomain to always block (matches that host and its subdomains). */
@Composable
private fun CustomDomainBlock() {
    val c = Warden.colors
    val rules by Rules.domains.collectAsState()
    val blocked = remember(rules) {
        rules.filter { (k, v) -> k.startsWith("*:") && v == Verdict.Block }
            .map { it.key.removePrefix("*:") }
            .sorted()
    }
    var input by rememberSaveable { mutableStateOf("") }
    val normalized = remember(input) { normalizeDomain(input) }
    val canAdd = normalized != null && normalized !in blocked

    SectionLabel(stringResource(R.string.bl_custom_title), Modifier.padding(top = Spacing.l))
    Text(stringResource(R.string.bl_custom_note), style = Warden.type.label, color = c.muted)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s)
            .clip(RoundedCornerShape(Radii.small))
            .background(c.surfaceVariant)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = Warden.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accentInk),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (canAdd) {
                        Rules.setEverywhere(normalized!!, Verdict.Block)
                        input = ""
                    }
                },
            ),
            decorationBox = { field ->
                Box {
                    if (input.isEmpty()) {
                        Text(stringResource(R.string.bl_custom_hint), style = Warden.type.body, color = c.muted)
                    }
                    field()
                }
            },
        )
        Text(
            stringResource(R.string.bl_custom_add),
            style = Warden.type.label,
            color = if (canAdd) c.accentInk else c.muted,
            modifier = Modifier
                .clickable(enabled = canAdd) {
                    Rules.setEverywhere(normalized!!, Verdict.Block)
                    input = ""
                }
                .padding(Spacing.s),
        )
    }
    if (blocked.isNotEmpty()) {
        Tile(Modifier.fillMaxWidth().padding(top = Spacing.s)) {
            blocked.forEachIndexed { i, domain ->
                if (i > 0) HorizontalDivider(color = c.outline)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(domain, Modifier.weight(1f), style = Warden.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.bl_custom_remove),
                        style = Warden.type.label,
                        color = c.blocked,
                        modifier = Modifier
                            .clickable { Rules.setEverywhere(domain, null) }
                            .padding(Spacing.s),
                    )
                }
            }
        }
    }
}

/** Lowercase host only; strips scheme/path/trailing dots. Null if not a plausible domain. */
private fun normalizeDomain(raw: String): String? {
    var s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    s = s.substringBefore('/').substringBefore('?').substringBefore('#').trimEnd('.')
    if (s.isEmpty() || '.' !in s || ' ' in s) return null
    if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
    return s
}

/**
 * What the lists (and your rules) actually stopped: blocked domains in a time range, most often first,
 * each with how many times, how many apps tried it, and when it last happened.
 */
@Composable
private fun BlockedDomains() {
    val c = Warden.colors
    var range by rememberSaveable { mutableStateOf(Range.Today) }
    val rows by remember(range) { History.blockedDomains(range) }.collectAsState(emptyList())
    val total = rows.sumOf { it.count }
    val rules by Rules.domains.collectAsState() // re-read each domain's rule when one changes
    SectionLabel(stringResource(R.string.bl_blocked_title), Modifier.padding(top = Spacing.l))
    SegmentedControl(
        listOf(stringResource(R.string.bl_range_today), stringResource(R.string.bl_range_week), stringResource(R.string.bl_range_month)),
        range.ordinal,
        { range = Range.entries[it] },
    )
    Tile(Modifier.fillMaxWidth()) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.bl_blocked_none), style = Warden.type.body, color = c.muted)
            return@Tile
        }
        // One line of context, then each domain with a bar for its share of all blocks.
        Text(
            pluralStringResource(R.plurals.bl_blocked_total, total, NumberFormat.getIntegerInstance().format(total), rows.size),
            style = Warden.type.body,
        )
        val most = rows.first().count.coerceAtLeast(1)
        val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
        // key(): open state follows the domain as the list re-sorts every minute.
        rows.forEach { d -> key(d.domain) {
            HorizontalDivider(color = c.outline)
            val rule = remember(rules, d.domain) { Rules.everywhere(d.domain) }
            val allowed = rule == Verdict.Allow
            // Tap to open the same rule chips as the live log; a domain here spans apps, so they're global.
            var open by rememberSaveable { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }.padding(vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(d.domain, Modifier.weight(1f), style = Warden.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (allowed) AllowedTag()
                    Text(
                        NumberFormat.getIntegerInstance().format(d.count),
                        style = Warden.type.body.tabular(),
                        color = if (allowed) c.muted else c.blocked,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.bl_blocked_apps, d.apps, d.apps) + " · " +
                        stringResource(R.string.bl_blocked_last, time.format(Date(d.lastTime))),
                    style = Warden.type.label,
                    color = c.muted,
                    maxLines = 1,
                )
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.surfaceVariant, RoundedCornerShape(2.dp))) {
                    Box(
                        Modifier.fillMaxWidth(d.count / most.toFloat()).height(3.dp)
                            .background((if (allowed) c.muted else c.blocked).copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
                    )
                }
                if (open) GlobalRuleChips(d.domain, rule, Modifier.padding(top = Spacing.xs))
            }
        } }
    }
}

/** Default (the lists decide), Allow everywhere, or Block everywhere: the live log's chips, for every app. */
@Composable
private fun GlobalRuleChips(domain: String, rule: Verdict?, modifier: Modifier = Modifier) {
    val options = listOf(null, Verdict.Allow, Verdict.Block)
    ChoiceChips(
        listOf(stringResource(R.string.bl_rule_default), stringResource(R.string.bl_rule_allow), stringResource(R.string.bl_rule_block)),
        options.indexOf(rule),
        { Rules.setEverywhere(domain, options[it]) },
        modifier,
    )
}

@Composable
private fun AllowedTag() {
    val c = Warden.colors
    Text(
        stringResource(R.string.settings_tag_allowed).uppercase(),
        Modifier.clip(RoundedCornerShape(Radii.small)).background(c.accentInk.copy(alpha = 0.14f))
            .padding(horizontal = Spacing.s, vertical = 2.dp),
        style = Warden.type.caption,
        color = c.accentInk,
    )
}
