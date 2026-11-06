package com.devanshu.devnex.core.designsystem.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.domain.model.Priority
import com.devanshu.devnex.domain.model.TaskStatus

/** Screen background. Plain and calm: the content is the focus. (Name kept from v1.) */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") intensity: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
}

/** The standard card: white surface, hairline border, 14dp corners. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nx = LocalNx.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, nx.line), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (accent != null) Modifier.height(IntrinsicSize.Min) else Modifier),
    ) {
        // A thin left stripe marks state (overdue, live, selected) without colouring the whole card.
        if (accent != null) Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(padding), content = content)
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                   icon: ImageVector? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Nx.Violet, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) = Pill(status.label, status.color(), modifier)

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = if (LocalNx.current.dark) .18f else .10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun PriorityBar(priority: Priority, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { i ->
            Box(Modifier.size(width = 4.dp, height = (6 + i * 2).dp).clip(RoundedCornerShape(1.dp))
                .background(if (i < priority.weight) priority.color() else LocalNx.current.line))
        }
    }
}

private val AvatarColors = listOf(0xFF1D4ED8, 0xFF0F766E, 0xFF7C3AED, 0xFFB45309, 0xFF0369A1, 0xFFBE123C, 0xFF4D7C0F, 0xFF475569)

@Composable
fun Avatar(name: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val c = Color(AvatarColors[(name.hashCode() and 0x7fffffff) % AvatarColors.size])
    Box(modifier.size(size).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
            color = Color.White, fontWeight = FontWeight.SemiBold,
            style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge)
    }
}

/** Completion ring for dashboards and projects. */
@Composable
fun PulseRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, label: String? = null, sub: String? = null) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    val track = LocalNx.current.line
    val arc = Nx.Violet
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx(); val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            drawArc(arc, -90f, 360f * animated, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label ?: "${(animated * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
        }
    }
}

@Composable
fun CountUp(value: Int, style: TextStyle, color: Color = Color.Unspecified) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { anim.animateTo(value.toFloat(), tween(700, easing = FastOutSlowInEasing)) }
    Text(anim.value.toInt().toString(), style = style, fontWeight = FontWeight.Bold, color = color)
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(.35f, .75f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(LocalNx.current.line.copy(alpha = a)))
}

@Composable
fun SkeletonList(count: Int = 4) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { SkeletonBox(Modifier.fillMaxWidth().height(84.dp)) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Nx.Violet.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Nx.Violet, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalNx.current.mute, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(10.dp))
            .background(Nx.Red.copy(alpha = .10f)).clickable(onClick = onDismiss).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Nx.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = Nx.Red, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun nxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Nx.Violet, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    cursorColor = Nx.Violet, focusedLabelColor = Nx.Violet,
)

/** Simple top bar used by secondary screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NxTopBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalNx.current.mute)
            }
        },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

fun relativeTime(ms: Long?): String {
    if (ms == null) return "—"
    val d = ms - System.currentTimeMillis()
    val abs = kotlin.math.abs(d)
    val txt = when {
        abs < 60_000 -> "now"
        abs < 3_600_000 -> "${abs / 60_000}m"
        abs < 86_400_000 -> "${abs / 3_600_000}h"
        else -> "${abs / 86_400_000}d"
    }
    return if (txt == "now") txt else if (d > 0) "in $txt" else "$txt ago"
}
