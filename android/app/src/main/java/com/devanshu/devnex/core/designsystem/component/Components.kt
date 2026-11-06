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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devanshu.devnex.core.designsystem.*
import com.devanshu.devnex.domain.model.Priority
import com.devanshu.devnex.domain.model.TaskStatus
import kotlin.math.cos
import kotlin.math.sin

/** Slow-moving aurora behind every screen — drawn on the GPU, ~0 cost on old phones. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, intensity: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    val nx = LocalNx.current
    val bg = MaterialTheme.colorScheme.background
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, 6.2832f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "t")
    Box(
        modifier.fillMaxSize().background(bg).drawBehind {
            val a = if (nx.dark) 0.22f * intensity else 0.10f * intensity
            fun blob(c: Color, x: Float, y: Float, r: Float) = drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
            blob(Nx.Violet, size.width * (0.2f + 0.15f * cos(t)), size.height * (0.15f + 0.05f * sin(t)), size.width * 0.9f)
            blob(Nx.Cyan, size.width * (0.9f + 0.1f * sin(t * 1.3f)), size.height * (0.45f + 0.08f * cos(t)), size.width * 0.7f)
            blob(Nx.Magenta, size.width * (0.3f + 0.2f * sin(t * 0.7f)), size.height * (0.95f), size.width * 0.6f)
        },
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nx = LocalNx.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(nx.glass)
            .border(BorderStroke(1.dp, if (accent != null) Brush.linearGradient(listOf(accent.copy(alpha = .6f), nx.glassBorder))
                                       else Brush.linearGradient(listOf(nx.glassBorder, nx.glassBorder))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                   icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(if (enabled) Nx.Signature else Brush.linearGradient(listOf(Nx.Line, Nx.Line)))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) = Pill(status.label, status.color(), modifier)

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun PriorityBar(priority: Priority, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { i ->
            Box(Modifier.size(width = 4.dp, height = 12.dp).clip(RoundedCornerShape(2.dp))
                .background(if (i < priority.weight) priority.color() else LocalNx.current.line))
        }
    }
}

@Composable
fun Avatar(name: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val hue = (name.hashCode() and 0x7fffffff) % 360
    val c1 = Color.hsv(hue.toFloat(), .55f, .95f); val c2 = Color.hsv(((hue + 50) % 360).toFloat(), .65f, .75f)
    Box(modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
        Text(name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
            color = Color.White, fontWeight = FontWeight.Bold, style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge)
    }
}

/** Circular progress “pulse” ring used on the dashboard and parent tasks. */
@Composable
fun PulseRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, label: String? = null, sub: String? = null) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing), label = "ring")
    val track = LocalNx.current.line
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx(); val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            drawArc(Brush.sweepGradient(listOf(Nx.Cyan, Nx.Violet, Nx.Magenta, Nx.Cyan)), -90f, 360f * animated, false, tl, Size(d, d),
                style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label ?: "${(animated * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontFamily = Mono)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute)
        }
    }
}

/** Numbers count up when they appear. */
@Composable
fun CountUp(value: Int, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(value) { anim.animateTo(value.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Text(anim.value.toInt().toString(), style = style, fontFamily = Mono, color = color)
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(.25f, .6f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(LocalNx.current.line.copy(alpha = a)))
}

@Composable
fun SkeletonList(count: Int = 4) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { SkeletonBox(Modifier.fillMaxWidth().height(92.dp)) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Nx.Violet.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Nx.VioletSoft, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalNx.current.mute,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp))
            .background(Nx.Red.copy(alpha = .15f)).clickable(onClick = onDismiss).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Nx.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = Nx.Red, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNx.current.mute, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun nxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Nx.Violet, unfocusedBorderColor = LocalNx.current.line,
    focusedContainerColor = LocalNx.current.glass, unfocusedContainerColor = LocalNx.current.glass,
    cursorColor = Nx.Cyan,
)

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
