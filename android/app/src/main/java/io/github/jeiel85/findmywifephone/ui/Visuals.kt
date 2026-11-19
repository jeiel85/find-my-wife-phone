package io.github.jeiel85.findmywifephone.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.core.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

// ---- the orb ------------------------------------------------------------------------------

enum class OrbMode { IDLE, WAITING, RINGING }

/**
 * The one big thing on the home screen. Idle it breathes; while a request is in flight sonar
 * rings travel outwards; while the other phone rings a sweep circles it.
 */
@Composable
fun Orb(
    label: String,
    icon: ImageVector,
    mode: OrbMode,
    color: Color,
    deep: Color,
    description: String,
    ringColor: Color = color,
    size: Dp = 210.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f), label = "press")
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "phase")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")

    Box(Modifier.size(size * 1.55f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r0 = size.toPx() / 2f
            val maxR = this.size.minDimension / 2f
            // Soft halo so the orb sits in light rather than on paper.
            drawCircle(Brush.radialGradient(listOf(ringColor.copy(alpha = 0.28f), Color.Transparent), center, maxR), maxR)
            if (mode != OrbMode.IDLE) {
                for (i in 0..2) {
                    val p = (phase + i / 3f) % 1f
                    drawCircle(ringColor.copy(alpha = (1f - p) * 0.6f), r0 + (maxR - r0) * p, style = Stroke(3.dp.toPx()))
                }
            }
            if (mode == OrbMode.RINGING) {
                rotate(sweep) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, ringColor), center),
                        startAngle = 0f, sweepAngle = 110f, useCenter = false,
                        topLeft = Offset(center.x - r0 - 14.dp.toPx(), center.y - r0 - 14.dp.toPx()),
                        size = Size((r0 + 14.dp.toPx()) * 2, (r0 + 14.dp.toPx()) * 2),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(
            Modifier
                .size(size)
                .scale(press * if (mode == OrbMode.IDLE) breathe else 1f)
                .shadow(28.dp, CircleShape, ambientColor = deep, spotColor = deep)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(Brush.radialGradient(listOf(color.lighten(0.22f), color, deep),
                        Offset(this.size.width * 0.34f, this.size.height * 0.28f), this.size.width * 0.95f))
                }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
        }
    }
}

fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

// ---- step tracker -------------------------------------------------------------------------

/** The real sequence of a request. [current] is the index reached; [failedAt] marks where it stopped. */
@Composable
fun StepTracker(steps: List<String>, current: Int, accent: Color, failedAt: Int? = null, done: Boolean = false) {
    val t = rememberInfiniteTransition(label = "steps")
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val fill by animateFloatAsState(current.coerceAtLeast(0) / (steps.size - 1f), tween(500), label = "fill")
    val line = P.c.hairline
    val failColor = P.c.danger
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val y = size.height / 2
                drawLine(line, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
                drawLine(if (failedAt != null) failColor else accent, Offset(0f, y), Offset(size.width * fill, y), 4.dp.toPx(), StrokeCap.Round)
                steps.indices.forEach { i ->
                    val x = size.width * i / (steps.size - 1)
                    val reached = i <= current
                    val isFail = failedAt == i
                    val c = when { isFail -> failColor; reached -> accent; else -> line }
                    val active = i == current && !done && failedAt == null
                    drawCircle(c, (if (active) 9.dp.toPx() * pulse else 7.dp.toPx()), Offset(x, y))
                    drawCircle(Color.White, 3.dp.toPx(), Offset(x, y))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            steps.forEachIndexed { i, s ->
                Text(s, Modifier.align(BiasAlignment(-1f + 2f * i / (steps.size - 1), 0f)), fontSize = 12.sp,
                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium,
                    color = if (i <= current) P.c.ink else P.c.inkSoft)
            }
        }
    }
}

// ---- the sofa (empty state) ---------------------------------------------------------------

/** The landing page's living room: a phone hides under the coral cushion and calls out. */
@Composable
fun SofaScene(modifier: Modifier = Modifier) {
    val c = P.c
    val t = rememberInfiniteTransition(label = "sofa")
    val wiggle by t.animateFloat(-2.5f, 2.5f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "w")
    val ripple by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    Canvas(modifier) {
        val s = size.width / 560f
        fun r(x: Float, y: Float, w: Float, h: Float, rad: Float, color: Color) =
            drawRoundRect(color, Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(rad * s))
        // lamp glow and lamp
        drawCircle(Brush.radialGradient(listOf(c.butter.copy(alpha = 0.55f), Color.Transparent), Offset(470 * s, 100 * s), 130 * s), 130 * s, Offset(470 * s, 100 * s))
        r(466f, 110f, 8f, 220f, 4f, if (c.dark) Color(0xFF3A3F66) else c.ink)
        drawPath(Path().apply { moveTo(430 * s, 112 * s); lineTo(510 * s, 112 * s); lineTo(490 * s, 62 * s); lineTo(450 * s, 62 * s); close() }, c.butter)
        // rug
        drawOval(c.sofaSoft.copy(alpha = 0.22f), Offset(30 * s, 318 * s), Size(500 * s, 66 * s))
        // plant
        drawPath(Path().apply { moveTo(70 * s, 320 * s); cubicTo(50 * s, 280 * s, 40 * s, 250 * s, 60 * s, 220 * s); cubicTo(70 * s, 250 * s, 74 * s, 280 * s, 70 * s, 320 * s); close() }, Color(0xFF4E8F6E))
        drawPath(Path().apply { moveTo(74 * s, 320 * s); cubicTo(90 * s, 290 * s, 110 * s, 270 * s, 116 * s, 240 * s); cubicTo(96 * s, 260 * s, 82 * s, 284 * s, 74 * s, 320 * s); close() }, Color(0xFF3E7A5C))
        drawPath(Path().apply { moveTo(56 * s, 318 * s); lineTo(92 * s, 318 * s); lineTo(87 * s, 350 * s); lineTo(61 * s, 350 * s); close() }, c.coral)
        // sofa
        val sofa = if (c.dark) Color(0xFF3340A8) else c.sofa
        val sofaDeep = Color(0xFF2A3590)
        r(130f, 160f, 340f, 120f, 34f, sofa)
        r(108f, 220f, 70f, 110f, 30f, sofaDeep)
        r(422f, 220f, 70f, 110f, 30f, sofaDeep)
        r(160f, 262f, 280f, 62f, 18f, Color(0xFF3D4BBE))
        r(162f, 322f, 14f, 16f, 4f, Color(0xFF1F2235))
        r(424f, 322f, 14f, 16f, 4f, Color(0xFF1F2235))
        // ripples from the hidden phone
        val origin = Offset(296 * s, 262 * s)
        for (i in 0..2) {
            val p = (ripple + i / 3f) % 1f
            drawCircle(c.coral.copy(alpha = (1f - p) * 0.8f), (24 + 120 * p) * s, origin, style = Stroke(3.dp.toPx()))
        }
        // phone peeking out
        rotate(-12f, Offset(292 * s, 270 * s)) {
            r(270f, 236f, 46f, 76f, 9f, Color(0xFF1F2235))
            r(274f, 242f, 38f, 62f, 5f, Color(0xFFFFF4DC))
        }
        // cushions
        rotate(-6f, Offset(206 * s, 236 * s)) { r(160f, 196f, 92f, 80f, 22f, c.butter) }
        rotate(wiggle, Offset(300 * s, 284 * s)) { r(252f, 196f, 98f, 84f, 24f, c.coral) }
        rotate(7f, Offset(394 * s, 237 * s)) { r(350f, 198f, 88f, 78f, 22f, c.sofaSoft) }
    }
}

// ---- mini map (OpenStreetMap tiles) -------------------------------------------------------

/**
 * OpenStreetMap's tile policy (operations.osmfoundation.org/policies/tiles): identify the app, show
 * the attribution, and keep tiles at least a week instead of fetching them again. Tiles are fetched
 * only when a shared location is on screen; the same place opened again comes from [dir].
 */
private object Tiles {
    private const val KEEP_MS = 7L * 86_400_000
    private const val MAX_FILES = 300
    private val cache = LruCache<String, ImageBitmap>(48)
    private val dir by lazy { java.io.File(Hub.context.cacheDir, "osm-tiles").apply { mkdirs() } }

    suspend fun load(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = java.io.File(dir, "$z-$x-$y.png")
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < KEEP_MS
                val bytes = if (fresh) file.readBytes() else download(key)?.also { save(file, it) }
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private fun download(key: String): ByteArray? {
        val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "find-my-wife-phone/${BuildConfig.VERSION_NAME} (+https://github.com/jeiel85/find-my-wife-phone)")
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    /** Oldest tiles go first once there are more than [MAX_FILES] (~5 MB). */
    private fun save(file: java.io.File, bytes: ByteArray) {
        file.writeBytes(bytes)
        val all = dir.listFiles() ?: return
        if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES).forEach { it.delete() }
    }
}

/** A real, small map around one fix, with the accuracy circle and a pin. */
@Composable
fun MiniMap(lat: Double, lng: Double, accuracyM: Float, modifier: Modifier = Modifier) {
    val c = P.c
    val zoom = when { accuracyM > 800 -> 14; accuracyM > 250 -> 15; else -> 16 }
    val n = 2.0.pow(zoom)
    val latRad = Math.toRadians(lat)
    val wx = (lng + 180) / 360 * n                       // world position in tile units
    val wy = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n
    val metersPerTile = 40_075_016.686 * cos(latRad) / n
    val tiles = remember(lat, lng, zoom) { mutableStateMapOf<Pair<Int, Int>, ImageBitmap>() }
    val cx = floor(wx).toInt(); val cy = floor(wy).toInt()
    LaunchedEffect(lat, lng, zoom) {
        coroutineScope {
            (-1..1).flatMap { dx -> (-1..1).map { dy -> (cx + dx) to (cy + dy) } }
                .map { (x, y) -> async { Tiles.load(zoom, x, y)?.let { tiles[x to y] = it } } }
                .awaitAll()
        }
    }
    val t = rememberInfiniteTransition(label = "pin")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "bob")
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(c.surfaceSoft)) {
        Canvas(Modifier.fillMaxSize()) {
            val tile = 150.dp.toPx()
            val ox = size.width / 2 - ((wx - cx) * tile).toFloat()
            val oy = size.height / 2 - ((wy - cy) * tile).toFloat()
            // grid fallback while tiles load / offline
            for (dx in -1..1) for (dy in -1..1) {
                val left = ox + dx * tile; val top = oy + dy * tile
                drawRect(c.hairline, Offset(left, top), Size(tile, tile), style = Stroke(1f))
                tiles[(cx + dx) to (cy + dy)]?.let { img ->
                    drawImage(img, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(tile.toInt() + 1, tile.toInt() + 1), alpha = if (c.dark) 0.78f else 1f)
                }
            }
            if (c.dark) drawRect(Color(0x55151832))
            val center = Offset(size.width / 2, size.height / 2)
            val accPx = (accuracyM / metersPerTile * tile).toFloat().coerceIn(10.dp.toPx(), size.minDimension * 0.6f)
            drawCircle(c.coral.copy(alpha = 0.16f), accPx, center)
            drawCircle(c.coral.copy(alpha = 0.6f), accPx, center, style = Stroke(1.5.dp.toPx()))
            drawPin(center, c.coral, lift = bob * 4.dp.toPx())
        }
        Text("© OpenStreetMap", Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(topStart = 8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 9.sp, color = Color(0xFF333333))
    }
}

private fun DrawScope.drawPin(at: Offset, color: Color, lift: Float) {
    val h = 34.dp.toPx(); val w = 26.dp.toPx()
    drawOval(Color.Black.copy(alpha = 0.18f), Offset(at.x - 7.dp.toPx(), at.y - 2.dp.toPx()), Size(14.dp.toPx(), 5.dp.toPx()))
    translate(0f, -lift) {
        val tip = at
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            cubicTo(tip.x - w * 0.15f, tip.y - h * 0.35f, tip.x - w / 2, tip.y - h * 0.5f, tip.x - w / 2, tip.y - h * 0.68f)
            cubicTo(tip.x - w / 2, tip.y - h * 1.02f, tip.x + w / 2, tip.y - h * 1.02f, tip.x + w / 2, tip.y - h * 0.68f)
            cubicTo(tip.x + w / 2, tip.y - h * 0.5f, tip.x + w * 0.15f, tip.y - h * 0.35f, tip.x, tip.y)
            close()
        }
        drawPath(path, color)
        drawCircle(Color.White, w * 0.2f, Offset(tip.x, tip.y - h * 0.68f))
    }
}

// ---- little pieces ------------------------------------------------------------------------

@Composable
fun StatusDot(color: Color, live: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = if (live) a else 1f)))
}

@Composable
fun Tile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(P.c.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(18.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (enabled) P.c.ink else P.c.inkSoft)
        Text(subtitle, fontSize = 13.sp, color = P.c.inkSoft, lineHeight = 18.sp)
    }
}
