package io.github.jeiel85.findmywifephone.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(P.c.surface)
            .border(1.dp, P.c.hairline, RoundedCornerShape(24.dp))
            .padding(padding),
    ) { content() }
}

@Composable
fun Avatar(name: String, color: Color, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).ifEmpty { "?" }, color = color, fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.42f).sp)
    }
}

/** Expanding rings behind the big button while something is in flight. */
@Composable
fun Pulse(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    if (!active) return
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(modifier) {
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = size.minDimension / 2f * (0.55f + 0.45f * p),
                style = Stroke(width = 3.dp.toPx()))
        }
    }
}

@Composable
fun PillButton(
    text: String,
    icon: ImageVector? = null,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .then(if (outlined) Modifier.border(1.5.dp, container, shape) else Modifier.background(if (enabled) container else container.copy(alpha = 0.4f)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (outlined) container else content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (outlined) container else content, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Chip(text: String, color: Color, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, bottom = 10.dp, top = 8.dp),
        style = MaterialTheme.typography.titleMedium, color = P.c.inkSoft)
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = P.c.ink,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = P.c.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
        }
        trailing()
    }
}

@Composable
fun CenterNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = P.c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, textAlign = TextAlign.Center)
    }
}

// ---- formatting --------------------------------------------------------------------------

fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - at).coerceAtLeast(0)
    return when {
        at == 0L -> "아직 없음"
        d < 60_000 -> "방금"
        d < 3_600_000 -> "${d / 60_000}분 전"
        d < 86_400_000 -> "${d / 3_600_000}시간 전"
        else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(at))
    }
}

fun clockTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(at))
    return if (sameDay) time else SimpleDateFormat("M월 d일 ", Locale.KOREA).format(Date(at)) + time
}

fun snoozeLabel(until: Long): String =
    if (until == io.github.jeiel85.findmywifephone.core.PeerStatus.FOREVER) "직접 끌 때까지" else "${clockTime(until)}까지"
