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
import androidx.compose.foundation.layout.offset
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

/** Pure animation, reusable (e.g. on the About screen). Calm, corporate, ~2.6 s. */
@Composable
fun DevNexSplashAnimation(modifier: Modifier = Modifier) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(2600, easing = LinearEasing)) }
    val t = clock.value
    fun phase(start: Float, end: Float) = FastOutSlowInEasing.transform(((t - start) / (end - start)).coerceIn(0f, 1f))
    val hexP = phase(0.00f, 0.40f)
    val nP = phase(0.30f, 0.62f)
    val wordP = phase(0.50f, 0.80f)
    val tagP = phase(0.70f, 0.95f)
    val bar = phase(0.15f, 1.00f)
    val brand = Nx.Brand
    val accent = Color(0xFF6E9BFF)

    Box(modifier.fillMaxSize().background(brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(112.dp)) {
                val c = center; val r = size.minDimension * .44f
                val hex = Path().apply {
                    for (i in 0..6) {
                        val a = ((-90f + 60f * i) * PI / 180f).toFloat()
                        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                drawPartial(hex, hexP, SolidColor(accent), 5.5f)
                val s = r * .50f
                val n = Path().apply { moveTo(c.x - s, c.y + s); lineTo(c.x - s, c.y - s); lineTo(c.x + s, c.y + s); lineTo(c.x + s, c.y - s) }
                drawPartial(n, nP, SolidColor(Color.White), 7f)
                if (nP >= 1f) {
                    drawCircle(Color.White, 5.5f, Offset(c.x - s, c.y + s))
                    drawCircle(accent, 5.5f, Offset(c.x + s, c.y - s))
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "DevNex",
                modifier = Modifier.alpha(wordP).offset(y = (14 * (1f - wordP)).dp),
                style = TextStyle(color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Company operations, in one place", modifier = Modifier.alpha(tagP),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .72f))
            Spacer(Modifier.height(36.dp))
            Box(Modifier.width(140.dp).height(3.dp).background(Color.White.copy(alpha = .15f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar).background(accent))
            }
        }
        Text("Powered by Devanshu", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).alpha(tagP),
            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .6f))
    }
}

private fun DrawScope.drawPartial(path: Path, progress: Float, brush: Brush, width: Float) {
    if (progress <= 0f) return
    val pm = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
    pm.getSegment(0f, pm.length * progress, seg, true)
    drawPath(seg, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
