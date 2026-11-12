package it.topcash.studio.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.topcash.studio.data.Tone
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.contentDescription

/** Springy press feedback: the element sinks slightly and bounces back. */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.98f else 1f, Bouncy, label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Material 3 "emphasized decelerate": quick start, long soft landing. Shared by every entrance in the app. */
val EmphasizedDecelerate = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Staggered entrance (fade + gentle rise + a hint of scale) the first time an element appears.
 *  It plays once: coming back to a screen (or scrolling an item back into view) shows it already in place. */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val a = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        kotlinx.coroutines.delay((index.coerceIn(0, 10) * 38).toLong())
        a.animateTo(1f, tween(560, easing = EmphasizedDecelerate)); shown = true
    }
    graphicsLayer {
        val v = a.value
        alpha = (v * 1.4f).coerceAtMost(1f) // opaque before the movement ends: no washed-out look
        translationY = (1f - v) * 18.dp.toPx()
        val sc = 0.985f + 0.015f * v; scaleX = sc; scaleY = sc
    }
}

fun initials(s: String): String = s.replace(Regex("[^\\p{L}\\p{N} ]"), "").split(Regex("\\s+"))
    .filter { it.length > 1 && it.lowercase() !in setOf("srl", "snc", "sas", "spa", "di", "il", "la", "lo", "s.r.l") }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "TC" }
/** Stable per-customer colour; lighter in dark mode so initials keep a good contrast. */
fun hueColor(key: String, dark: Boolean = false): Color { var h = 0; key.forEach { h = (h * 31 + it.code) % 360 }; return Color.hsl(h.toFloat(), if (dark) 0.62f else 0.58f, if (dark) 0.70f else 0.45f) }

@Composable fun Avatar(name: String, key: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.2f
    val c = hueColor(key, dark)
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(c.copy(alpha = if (dark) 0.18f else 0.14f)), contentAlignment = Alignment.Center) {
        Text(initials(name), color = c, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.33f).sp, maxLines = 1)
    }
}

@Composable fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = LocalStatus.current.of(tone)
    val bg by animateColorAsState(c.copy(alpha = 0.13f), label = "pill")
    Row(modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
        Text(text, color = c, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable fun CardBox(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressScale(src) else Modifier),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.then(if (onClick != null) Modifier.clickable(src, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier).padding(padding), content = content)
    }
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Odometer: every digit rolls on its own when the value changes (up when it grows, down when it shrinks); first appearance rolls in staggered. */
@Composable fun RollingText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    var shown by remember { mutableStateOf(text.map { c -> if (c.isDigit()) ' ' else c }.joinToString("")) }
    var grew by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(text) {
        val a = shown.filter { it.isDigit() }.toBigIntegerOrNull(); val b = text.filter { it.isDigit() }.toBigIntegerOrNull()
        grew = a == null || b == null || b >= a; shown = text
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val n = shown.length
        shown.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(n - i) { // slots counted from the right: units stay units when the number gets longer
                androidx.compose.animation.AnimatedContent(ch, transitionSpec = {
                    val dir = if (grew) 1 else -1; val delay = ((n - 1 - i) * 28).coerceAtMost(220)
                    val spec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.72f, stiffness = 420f)
                    ((androidx.compose.animation.slideInVertically(spec) { h -> h * dir } + androidx.compose.animation.fadeIn(tween(180, delayMillis = delay)))
                        togetherWith (androidx.compose.animation.slideOutVertically(spec) { h -> -h * dir } + androidx.compose.animation.fadeOut(tween(140))))
                        .using(androidx.compose.animation.SizeTransform(clip = true))
                }, label = "digit") { c -> Text(c.toString(), style = style, color = color, maxLines = 1) }
            }
        }
    }
}

@Composable fun Kpi(title: String, value: Int, note: String, icon: ImageVector, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalStatus.current.of(tone)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c, modifier = Modifier.size(17.dp)) }
        }
        RollingText("%,d".format(value).replace(',', '.'), MaterialTheme.typography.headlineLarge.tabular, Modifier.padding(top = 14.dp, bottom = 4.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun Empty(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** The area switch shown in the top bar of the main pages (null = the user has only one area). */
val LocalAreaSwitch = androidx.compose.runtime.staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** "Interventi | Contabilità": a pill with a sliding thumb, like the site and the Windows app. */
@Composable fun AreaSwitch(area: String, onSelect: (String) -> Unit) {
    // compact: the active area shows icon + name, the other one only its icon (the page title keeps its room)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val items = listOf("ops" to ("Interventi" to androidx.compose.material.icons.Icons.Rounded.Build), "acc" to ("Contabilità" to androidx.compose.material.icons.Icons.Rounded.Euro))
    val spring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.85f, stiffness = 500f)
    Row(Modifier.padding(end = 8.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp)
        .animateContentSize(spring), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (key, v) ->
            val on = key == area
            val accent = if (key == "acc") LocalStatus.current.ok else MaterialTheme.colorScheme.primary
            val bg by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent, label = "bg")
            val tint by androidx.compose.animation.animateColorAsState(if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant, label = "t")
            Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(bg)
                .clickable(onClickLabel = v.first) { if (!on) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); onSelect(key) } }
                .semantics { role = androidx.compose.ui.semantics.Role.Tab; selected = on; contentDescription = v.first }
                .padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(v.second, null, Modifier.size(18.dp), tint = tint)
                androidx.compose.animation.AnimatedVisibility(on, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, 60)) + androidx.compose.animation.expandHorizontally(),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) + androidx.compose.animation.shrinkHorizontally()) {
                    Text(v.first, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
    }
}

/** One option of [SelectField]; [group] starts a new labelled section when it changes. */
data class SelectOption(val value: String, val label: String = value, val group: String = "", val supporting: String = "")

/** Material 3 select: an outlined field that opens an anchored menu, grouped, with the chosen entry ticked. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun SelectField(label: String, value: String, options: List<SelectOption>, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.value == value }?.label ?: value.ifBlank { "Nessuno" }
    androidx.compose.material3.ExposedDropdownMenuBox(open && enabled, { open = it }, modifier) {
        androidx.compose.material3.OutlinedTextField(shown, {}, Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true, enabled = enabled, singleLine = true, label = { Text(label) }, shape = MaterialTheme.shapes.medium,
            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open && enabled, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            var last = "\u0000"
            options.forEach { o ->
                if (o.group != last) { last = o.group; if (o.group.isNotBlank()) Text(o.group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)) }
                val sel = o.value == value
                androidx.compose.material3.DropdownMenuItem(
                    text = { Column { Text(o.label, maxLines = 1, fontWeight = if (sel) FontWeight.SemiBold else null); if (o.supporting.isNotBlank()) Text(o.supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    trailingIcon = { if (sel) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onChange(o.value); open = false })
            }
        }
    }
}

/** Team as select options: technicians first, then office and administrators; a name typed in the past stays selectable. */
fun teamOptions(team: List<it.topcash.studio.data.TeamMember>, current: String): List<SelectOption> {
    val groupOf = mapOf("tecnico" to "Tecnici", "ufficio" to "Amministrazione", "admin" to "Amministratori", "owner" to "Amministratori")
    val order = listOf("Tecnici", "Amministrazione", "Amministratori", "Team")
    val people = team.map { SelectOption(it.name, it.name, groupOf[it.role] ?: "Team") }.sortedWith(compareBy({ order.indexOf(it.group) }, { it.label.lowercase() }))
    val old = if (current.isNotBlank() && people.none { it.value.equals(current, true) }) listOf(SelectOption(current, current, "Altro")) else emptyList()
    return listOf(SelectOption("", "Nessuno")) + people + old
}

/** Modal sheets open straight to full height (no half-way stop): Material 3 state API. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun rememberSheet(): androidx.compose.material3.SheetState = androidx.compose.material3.rememberBottomSheetState(
    androidx.compose.material3.SheetValue.Hidden, setOf(androidx.compose.material3.SheetValue.Hidden, androidx.compose.material3.SheetValue.Expanded))

/** Label/value row; long-press copies the value. */
@Composable fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val clip = androidx.compose.ui.platform.LocalClipboard.current; val haptic = LocalHapticFeedback.current; val copyScope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled = value.isNotBlank(), onClick = {}, onLongClick = { copyScope.launch { clip.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText(label, value))) }; haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
        .padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable fun DateChip(day: Int, month: String, tone: Tone) {
    val c = LocalStatus.current.of(tone)
    Column(Modifier.size(width = 46.dp, height = 50.dp).clip(RoundedCornerShape(14.dp)).background(if (tone == Tone.Neutral) MaterialTheme.colorScheme.surfaceVariant else c.copy(alpha = 0.12f)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("$day", style = MaterialTheme.typography.titleMedium, color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else c, lineHeight = 18.sp)
        Text(month.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else c)
    }
}

@Composable fun Gap(h: Dp) = Spacer(Modifier.height(h))

/** Rounded search field: one-line placeholder (never wraps), clear button. */
@Composable fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, focus: Boolean = false, onFocused: () -> Unit = {}) {
    val fr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(focus) { if (focus) { kotlinx.coroutines.delay(350); runCatching { fr.requestFocus() }; onFocused() } }
    androidx.compose.material3.OutlinedTextField(value, onChange, modifier.fillMaxWidth().focusRequester(fr),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) androidx.compose.material3.IconButton({ onChange("") }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Clear, "Cancella") } },
        singleLine = true, shape = MaterialTheme.shapes.extraLarge,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
}

/** Horizontally scrolling single-choice chips: labels are never truncated, on any screen width. */
@Composable fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    // full bleed: the row runs edge to edge (chips slide under the screen edge, not under an invisible margin) but starts aligned with the page
    // the chosen chip is always in sight: a tap near the edge (or a choice made for you, like your own round) slides it into view
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selected, options.size) {
        val i = options.indexOf(selected); if (i < 0) return@LaunchedEffect
        val seen = row.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
        val inside = seen != null && seen.offset >= row.layoutInfo.viewportStartOffset && seen.offset + seen.size <= row.layoutInfo.viewportEndOffset - 16
        if (!inside) row.animateScrollToItem(maxOf(0, i - 1))
    }
    androidx.compose.foundation.lazy.LazyRow(modifier.bleed(16.dp), state = row, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(options.size) { i -> val o = options[i]
            androidx.compose.material3.FilterChip(selected == o, { onSelect(o) }, label = { Text(o, maxLines = 1) }, shape = MaterialTheme.shapes.small,
                leadingIcon = if (selected == o) { { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
        }
    }
}

/** Widens an element by [h] on both sides, cancelling the page's side padding (horizontal scrollers go edge to edge). */
fun Modifier.bleed(h: Dp): Modifier = this.layout { m, c ->
    val extra = h.roundToPx() * 2
    val p = m.measure(c.copy(minWidth = (c.minWidth + extra).coerceAtLeast(0), maxWidth = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth))
    layout((p.width - extra).coerceIn(c.minWidth, c.maxWidth), p.height) { p.place(-h.roundToPx(), 0) }
}

/**
 * Segmented choice with a sliding thumb: the chosen option sits on a raised pill that glides (spring) under the
 * labels, no tick taking room from the text. Labels shrink a step when the screen is narrow, never touch the edges
 * and never wrap. One haptic tick per change. [accent] colours the label of the chosen option (e.g. priority).
 */
@Composable fun SegmentedPicker(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
                                enabled: Boolean = true, accent: (Int) -> Color? = { null }) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(50)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(48.dp).clip(pill).background(cs.surfaceContainerHigh).padding(4.dp)) {
        val n = options.size.coerceAtLeast(1); val w = maxWidth / n
        val x by androidx.compose.animation.core.animateDpAsState(w * selected.coerceIn(0, n - 1),
            androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 520f), label = "thumb")
        // the raised pill is lighter than the track in both themes (in dark mode "surface" is darker than the track and looked black)
        val base = if (cs.surface.luminance() < 0.5f) cs.surfaceBright else cs.surface
        // the chosen option tints the pill softly with its own colour (priority: red, yellow, green; Normale stays neutral)
        val tinted = accent(selected.coerceIn(0, n - 1))?.copy(alpha = 0.2f)?.compositeOver(base) ?: base
        val thumb by androidx.compose.animation.animateColorAsState(if (enabled) tinted else tinted.copy(alpha = 0.75f), label = "thumb")
        if (selected in options.indices) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight()
            .shadow(if (enabled) 2.dp else 0.dp, pill, clip = false).clip(pill).background(thumb))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val tint by androidx.compose.animation.animateColorAsState(
                    if (on) accent(i) ?: cs.onSurface else cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f), label = "label")
                Box(Modifier.weight(1f).fillMaxHeight().clip(pill)
                    .selectable(on, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab) {
                        if (!on) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); onSelect(i) } }
                    .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.text.BasicText(label, maxLines = 1, softWrap = false, color = { tint },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp))
                }
            }
        }
    }
}

/**
 * Action button with the icon above the label, for rows of 2-4 actions (Carico / Scarico / Trasferisci, Stampa /
 * Condividi / Email). The label never wraps and never gets cut: it shrinks a step when the phone uses large text.
 */
@Composable fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                           primary: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val bg = if (primary) cs.primary else cs.secondaryContainer
    val fg = (if (primary) cs.onPrimary else cs.onSecondaryContainer).let { if (enabled) it else it.copy(alpha = 0.5f) }
    Column(modifier.heightIn(min = 68.dp).clip(MaterialTheme.shapes.medium).background(if (enabled) bg else bg.copy(alpha = 0.5f))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); onClick() }
        .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
        androidx.compose.material3.Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        androidx.compose.foundation.text.BasicText(label, maxLines = 1, softWrap = false, color = { fg },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp))
    }
}

/** Priority colours, the same on the website: Urgente red, Alta yellow, Bassa green, Normale none. */
fun prioTone(p: String): Tone? = when (p) { "Urgente" -> Tone.Bad; "Alta" -> Tone.Due; "Bassa" -> Tone.Ok; else -> null }
@Composable fun prioColor(p: String): Color? = prioTone(p)?.let { LocalStatus.current.of(it) }
