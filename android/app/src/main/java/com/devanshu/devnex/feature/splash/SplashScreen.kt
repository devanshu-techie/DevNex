package com.devanshu.devnex.feature.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devanshu.devnex.core.designsystem.Mono
import com.devanshu.devnex.core.designsystem.Nx
import com.devanshu.devnex.domain.repository.AuthRepository
import com.devanshu.devnex.domain.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class StartDestination { LOGIN, MFA, WORKSPACES, MAIN }

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val workspaces: WorkspaceRepository,
) : ViewModel() {
    var destination by mutableStateOf<StartDestination?>(null); private set

    init {
        viewModelScope.launch {
            // Restore the session while the animation plays; never block on it longer than the animation.
            val work = async {
                val user = runCatching { auth.restore() }.getOrNull()
                when {
                    user == null -> StartDestination.LOGIN
                    user.needsMfa -> StartDestination.MFA
                    else -> {
                        val list = runCatching { workspaces.refresh() }.getOrDefault(emptyList())
                        if (workspaces.active.value != null) StartDestination.MAIN
                        else if (list.isEmpty()) StartDestination.WORKSPACES else StartDestination.WORKSPACES
                    }
                }
            }
            delay(2900)
            destination = work.await()
        }
    }
}

@Composable
fun SplashScreen(onDone: (StartDestination) -> Unit, vm: SplashViewModel = hiltViewModel()) {
    val dest = vm.destination
    LaunchedEffect(dest) { if (dest != null) onDone(dest) }
    DevNexSplashAnimation()
}

private const val WORD = "DEVNEX"
private const val GLYPHS = "01ΔΣΞ#@%&*<>/\\|{}[]ABCDEFGHJKLMNPQRSTUVWXYZ"

/** Pure animation, reusable (e.g. on the About screen). */
@Composable
fun DevNexSplashAnimation(modifier: Modifier = Modifier) {
    // master clock 0 → 1 over 2.6 s
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(2600, easing = LinearEasing)) }
    val t = clock.value

    val inf = rememberInfiniteTransition(label = "splash")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "drift")

    val stars = remember { List(80) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    fun phase(start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
    val hexP = FastOutSlowInEasing.transform(phase(0.0f, 0.38f))
    val circuitP = FastOutSlowInEasing.transform(phase(0.30f, 0.62f))
    val nodesP = phase(0.55f, 0.70f)
    val textP = phase(0.55f, 0.92f)
    val tagP = phase(0.82f, 1.0f)
    val scan = phase(0.10f, 0.75f)

    Box(modifier.fillMaxSize().background(Nx.Void), contentAlignment = Alignment.Center) {
        // starfield + aurora + scan line
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Nx.Violet.copy(alpha = .28f * hexP), Color.Transparent),
                center, size.minDimension * .75f), size.minDimension * .75f, center)
            stars.forEach { (x, y, z) ->
                val yy = ((y + drift * (0.05f + z * 0.12f)) % 1f) * size.height
                drawCircle(Color.White.copy(alpha = 0.15f + 0.6f * z * (0.5f + 0.5f * sin((drift * 20 + x * 10)))),
                    radius = 0.6f + z * 1.8f, center = Offset(x * size.width, yy))
            }
            if (scan in 0.001f..0.999f) {
                val y = size.height * scan
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Nx.Cyan.copy(alpha = .18f), Color.Transparent), y - 60f, y + 60f),
                    topLeft = Offset(0f, y - 60f), size = androidx.compose.ui.geometry.Size(size.width, 120f))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // logo mark
            Canvas(Modifier.size(150.dp)) {
                val c = center; val r = size.minDimension * .40f
                // orbit ring with satellites
                rotate(spin, c) {
                    drawCircle(Nx.Cyan.copy(alpha = .18f * hexP), r * 1.22f, c, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))))
                    for (i in 0 until 3) {
                        val a = (i * 120f) * (PI / 180f).toFloat()
                        drawCircle(listOf(Nx.Cyan, Nx.Violet, Nx.Magenta)[i].copy(alpha = hexP), 4.5f,
                            Offset(c.x + r * 1.22f * cos(a), c.y + r * 1.22f * sin(a)))
                    }
                }
                // hexagon draws itself
                val hex = Path().apply {
                    for (i in 0..6) {
                        val a = ((-90f + 60f * i) * PI / 180f).toFloat()
                        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                drawPartial(hex, hexP, Brush.linearGradient(listOf(Nx.Violet, Nx.Cyan), Offset(0f, 0f), Offset(size.width, size.height)), 7f, glow = true)

                // the N circuit
                val s = r * .52f
                val n = Path().apply {
                    moveTo(c.x - s, c.y + s); lineTo(c.x - s, c.y - s); lineTo(c.x + s, c.y + s); lineTo(c.x + s, c.y - s)
                }
                drawPartial(n, circuitP, SolidColor(Color.White), 9f, glow = true)

                // nodes pop
                if (nodesP > 0f) {
                    val k = (if (nodesP < .6f) nodesP / .6f * 1.3f else 1.3f - (nodesP - .6f) / .4f * .3f) * pulse
                    drawCircle(Nx.Violet.copy(alpha = .35f), 16f * k, Offset(c.x - s, c.y + s))
                    drawCircle(Nx.Violet, 7f * k, Offset(c.x - s, c.y + s))
                    drawCircle(Nx.Cyan.copy(alpha = .35f), 16f * k, Offset(c.x + s, c.y - s))
                    drawCircle(Nx.Cyan, 7f * k, Offset(c.x + s, c.y - s))
                }
            }

            Spacer(Modifier.height(28.dp))

            // "decrypting" wordmark
            val tick = (t * 60).toInt()
            Row {
                WORD.forEachIndexed { i, ch ->
                    val local = ((textP * (WORD.length + 3)) - i).coerceIn(0f, 1f)
                    val shown = when {
                        local <= 0f -> ' '
                        local < 1f -> GLYPHS[(tick * 7 + i * 13) % GLYPHS.length]
                        else -> ch
                    }
                    Text(
                        shown.toString(),
                        modifier = Modifier.alpha(if (local <= 0f) 0f else .4f + .6f * local),
                        style = TextStyle(
                            brush = if (local >= 1f) Brush.linearGradient(listOf(Color.White, if (i < 3) Nx.VioletSoft else Nx.Cyan)) else SolidColor(Nx.Cyan),
                            fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp,
                            fontFamily = if (local >= 1f) null else Mono,
                            shadow = Shadow(if (i < 3) Nx.Violet else Nx.Cyan, Offset.Zero, 24f * local),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("EVERY TASK · EVERY TEAM · ONE NEXUS", modifier = Modifier.alpha(tagP),
                style = MaterialTheme.typography.labelSmall, color = Nx.Mute)
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp).alpha(tagP),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(120.dp).height(2.dp).background(Nx.Signature, alpha = .8f))
            Spacer(Modifier.height(12.dp))
            Text("POWERED BY DEVANSHU", style = MaterialTheme.typography.labelSmall, color = Nx.Mute)
        }
    }
}

private fun DrawScope.drawPartial(path: Path, progress: Float, brush: Brush, width: Float, glow: Boolean) {
    if (progress <= 0f) return
    val pm = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    if (glow) drawPath(seg, brush, alpha = .25f, style = Stroke(width * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(seg, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // spark at the drawing head
    if (progress < 1f) {
        val head = pm.getPosition(pm.length * progress)
        drawCircle(Color.White, width * .9f, head)
        drawCircle(Nx.Cyan.copy(alpha = .4f), width * 2.4f, head)
    }
}
