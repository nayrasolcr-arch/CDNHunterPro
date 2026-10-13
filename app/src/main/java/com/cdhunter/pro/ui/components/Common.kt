package com.cdhunter.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cdhunter.pro.R
import com.cdhunter.pro.core.model.Verdict
import java.util.Locale

/** Big-number stat card used on the dashboard. */
@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accent ?: MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Verdict chip with consistent color coding across screens. */
@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier) {
    val (label, color) = when (verdict) {
        Verdict.VERIFIED_COMPATIBLE -> stringResource(R.string.verdict_verified) to Color(0xFF2E7D32)
        Verdict.CANDIDATE_NEEDS_TESTING -> stringResource(R.string.verdict_candidate) to Color(0xFF1565C0)
        Verdict.REACHABLE_INCOMPATIBLE -> stringResource(R.string.verdict_reachable_bad) to Color(0xFFEF6C00)
        Verdict.UNREACHABLE -> stringResource(R.string.verdict_unreachable) to Color(0xFFC62828)
        Verdict.EXPIRED -> stringResource(R.string.verdict_expired) to Color(0xFF6D4C41)
        Verdict.INSUFFICIENT_DATA -> stringResource(R.string.verdict_insufficient) to Color(0xFF546E7A)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Simple live line chart (latency series, scan history). */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = true,
) {
    val maxValue = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        if (values.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - (v / maxValue) * h * 0.9f - h * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val fillPath = Path().apply {
                addPath(path)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(fillPath, lineColor.copy(alpha = 0.15f))
        }
        drawPath(path, lineColor, style = Stroke(width = 4f))
    }
}

/** Horizontal bar chart for per-IP comparisons. */
@Composable
fun BarChart(
    entries: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    val max = (entries.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(0.0001f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (label, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize(fraction = (v / max).coerceIn(0.02f, 1f))
                            .background(barColor),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ formatters

object Fmt {
    fun ms(v: Int?): String = if (v == null) "–" else String.format(Locale.US, "%,d ms", v)
    fun kbps(v: Double?): String = when {
        v == null -> "–"
        v >= 1_000 -> String.format(Locale.US, "%.2f Mbps", v / 1_000.0)
        else -> String.format(Locale.US, "%.0f KB/s", v)
    }
    fun pct(v: Double?): String = if (v == null) "–" else String.format(Locale.US, "%.0f%%", v)
    fun eta(sec: Long): String = when {
        sec < 0 -> "–"
        sec < 60 -> "${sec}s"
        sec < 3_600 -> "${sec / 60}m ${(sec % 60)}s"
        else -> "${sec / 3_600}h ${(sec % 3_600) / 60}m"
    }
    fun bytes(b: Long): String = when {
        b < 1_000_000 -> String.format(Locale.US, "%.0f KB", b / 1_000.0)
        else -> String.format(Locale.US, "%.1f MB", b / 1_000_000.0)
    }
    fun time(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(ms))
}
