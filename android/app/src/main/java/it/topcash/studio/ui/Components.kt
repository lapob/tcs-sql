package it.topcash.studio.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.topcash.studio.data.Tone

/** Springy press feedback: the element sinks slightly and bounces back. */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.965f else 1f, Bouncy, label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Staggered entrance (fade + rise) the first time an element appears. */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay((index.coerceAtMost(12) * 45).toLong()); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28.dp.toPx() }
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
        Text(text, color = c, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

@Composable fun Kpi(title: String, value: Int, note: String, icon: ImageVector, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shown by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "kpi")
    val c = LocalStatus.current.of(tone)
    CardBox(modifier, onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c, modifier = Modifier.size(17.dp)) }
        }
        Text("%,d".format(shown).replace(',', '.'), style = MaterialTheme.typography.headlineLarge.tabular, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** Label/value row; long-press copies the value. */
@Composable fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current; val haptic = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .combinedClickable(enabled = value.isNotBlank(), onClick = {}, onLongClick = { clip.setText(AnnotatedString(value)); haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
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
@Composable fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedTextField(value, onChange, modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) androidx.compose.material3.IconButton({ onChange("") }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Clear, "Cancella") } },
        singleLine = true, shape = MaterialTheme.shapes.extraLarge,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
}

/** Horizontally scrolling single-choice chips: labels are never truncated, on any screen width. */
@Composable fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i -> val o = options[i]
            androidx.compose.material3.FilterChip(selected == o, { onSelect(o) }, label = { Text(o, maxLines = 1) }, shape = MaterialTheme.shapes.small,
                leadingIcon = if (selected == o) { { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
        }
    }
}
