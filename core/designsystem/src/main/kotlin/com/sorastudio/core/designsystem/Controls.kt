package com.sorastudio.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sorastudio.core.common.LevelMath
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SoraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape),
        color = MaterialTheme.colorScheme.surface,
        shape = shape
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

/**
 * Rotary knob, 0..1 domain. Vertical drag adjusts; arc shows value.
 * [bipolar] draws from centre (for pan).
 */
@Composable
fun RotaryKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    size: Dp = 52.dp,
    bipolar: Boolean = false,
    accent: Color = SoraPalette.Accent,
    onDragStart: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null
) {
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val track = MaterialTheme.colorScheme.surfaceVariant
        val face = MaterialTheme.colorScheme.surface
        val outline = MaterialTheme.colorScheme.outline
        Canvas(
            modifier = Modifier
                .size(size)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragValue = value; onDragStart?.invoke() },
                        onDragEnd = { onDragEnd?.invoke() },
                        onDragCancel = { onDragEnd?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragValue = (dragValue - dragAmount.y / 180f).coerceIn(0f, 1f)
                            onValueChange(dragValue)
                        }
                    )
                }
        ) {
            val stroke = 3.5.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val startAngle = 135f
            val fullSweep = 270f
            // track
            drawArc(color = track, startAngle = startAngle, sweepAngle = fullSweep, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // value
            val v = value.coerceIn(0f, 1f)
            if (bipolar) {
                val centre = startAngle + fullSweep / 2
                val target = startAngle + fullSweep * v
                val from = minOf(centre, target)
                val sweep = kotlin.math.abs(target - centre)
                if (sweep > 0.5f) {
                    drawArc(color = accent, startAngle = from, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            } else if (v > 0.001f) {
                drawArc(color = accent, startAngle = startAngle, sweepAngle = fullSweep * v, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // face
            val centreOffset = Offset(this.size.width / 2, this.size.height / 2)
            val faceRadius = this.size.minDimension / 2 - 6.dp.toPx()
            drawCircle(color = face, radius = faceRadius, center = centreOffset)
            drawCircle(color = outline, radius = faceRadius, center = centreOffset, style = Stroke(0.75.dp.toPx()))
            // pointer
            val angleRad = Math.toRadians((startAngle + fullSweep * v - 90f).toDouble())
            val pointerEnd = Offset(
                centreOffset.x + cos(angleRad).toFloat() * (faceRadius - 3.dp.toPx()),
                centreOffset.y + sin(angleRad).toFloat() * (faceRadius - 3.dp.toPx())
            )
            val pointerStart = Offset(
                centreOffset.x + cos(angleRad).toFloat() * (faceRadius * 0.45f),
                centreOffset.y + sin(angleRad).toFloat() * (faceRadius * 0.45f)
            )
            drawLine(color = accent, start = pointerStart, end = pointerEnd, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (valueText != null) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Vertical mixer fader, 0..1 domain (map with [LevelMath]). */
@Composable
fun VerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    accent: Color = SoraPalette.Accent,
    onDragStart: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val handle = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .width(36.dp)
            .height(height)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onDragStart?.invoke()
                        onValueChange((1f - offset.y / this.size.height).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onDragEnd?.invoke() },
                    onDragCancel = { onDragEnd?.invoke() },
                    onDrag = { change, _ ->
                        change.consume()
                        onValueChange((1f - change.position.y / this.size.height).coerceIn(0f, 1f))
                    }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val railWidth = 4.dp.toPx()
            val railX = size.width / 2 - railWidth / 2
            drawRoundRect(color = track, topLeft = Offset(railX, 6.dp.toPx()),
                size = Size(railWidth, size.height - 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(railWidth / 2))
            // fill from bottom to value
            val v = value.coerceIn(0f, 1f)
            val fillHeight = (size.height - 12.dp.toPx()) * v
            drawRoundRect(color = accent, topLeft = Offset(railX, size.height - 6.dp.toPx() - fillHeight),
                size = Size(railWidth, fillHeight), cornerRadius = androidx.compose.ui.geometry.CornerRadius(railWidth / 2))
            // handle
            val handleY = size.height - 6.dp.toPx() - fillHeight
            val handleW = 26.dp.toPx()
            val handleH = 11.dp.toPx()
            drawRoundRect(color = handle, topLeft = Offset(size.width / 2 - handleW / 2, handleY - handleH / 2),
                size = Size(handleW, handleH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            drawLine(color = Color(0xFF101114), start = Offset(size.width / 2 - 8.dp.toPx(), handleY),
                end = Offset(size.width / 2 + 8.dp.toPx(), handleY), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** Peak + RMS bar meter. Values are linear 0..1+ (clipped visually). */
@Composable
fun StereoMeter(
    peakL: Float,
    peakR: Float,
    rmsL: Float,
    rmsR: Float,
    modifier: Modifier = Modifier,
    vertical: Boolean = true
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val topDb = 3f
        val bottomDb = -48f
        fun levelToFrac(level: Float): Float {
            val db = LevelMath.gainToDb(level)
            return ((db - bottomDb) / (topDb - bottomDb)).coerceIn(0f, 1f)
        }
        fun colorForFrac(frac: Float): Color = when {
            frac > 0.92f -> SoraPalette.Error
            frac > 0.78f -> SoraPalette.Warning
            else -> SoraPalette.Success
        }
        if (vertical) {
            val barW = (size.width - 2.dp.toPx()) / 2
            listOf(peakL to rmsL, peakR to rmsR).forEachIndexed { index, (peak, rms) ->
                val x = index * (barW + 2.dp.toPx())
                drawRect(color = track, topLeft = Offset(x, 0f), size = Size(barW, size.height))
                val rmsFrac = levelToFrac(rms)
                val rmsHeight = size.height * rmsFrac
                drawRect(color = colorForFrac(rmsFrac).copy(alpha = 0.55f),
                    topLeft = Offset(x, size.height - rmsHeight), size = Size(barW, rmsHeight))
                val peakFrac = levelToFrac(peak)
                val peakY = size.height * (1f - peakFrac)
                drawRect(color = colorForFrac(peakFrac), topLeft = Offset(x, peakY - 1.dp.toPx()), size = Size(barW, 2.dp.toPx()))
            }
        } else {
            val barH = (size.height - 2.dp.toPx()) / 2
            listOf(peakL to rmsL, peakR to rmsR).forEachIndexed { index, (peak, rms) ->
                val y = index * (barH + 2.dp.toPx())
                drawRect(color = track, topLeft = Offset(0f, y), size = Size(size.width, barH))
                val rmsFrac = levelToFrac(rms)
                drawRect(color = colorForFrac(rmsFrac).copy(alpha = 0.55f),
                    topLeft = Offset(0f, y), size = Size(size.width * rmsFrac, barH))
                val peakFrac = levelToFrac(peak)
                drawRect(color = colorForFrac(peakFrac), topLeft = Offset(size.width * peakFrac - 1.dp.toPx(), y), size = Size(2.dp.toPx(), barH))
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = SoraPalette.Accent,
    size: Dp = 40.dp
) {
    val bg = if (active) activeColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (active) activeColor else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun SmallToggleButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = SoraPalette.Accent
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (active) Color(0xFF101114) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
fun TransportBar(
    isPlaying: Boolean,
    isRecording: Boolean,
    positionText: String,
    tempoText: String,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSkipStart: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(
                icon = androidx.compose.material.icons.Icons.Filled.SkipPrevious,
                contentDescription = "Back to start",
                onClick = onSkipStart,
                size = 36.dp
            )
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                icon = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                active = isPlaying,
                size = 44.dp
            )
            Spacer(Modifier.width(6.dp))
            RoundIconButton(
                icon = androidx.compose.material.icons.Icons.Filled.Stop,
                contentDescription = "Stop",
                onClick = onStop,
                size = 36.dp
            )
            if (isRecording) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(SoraPalette.Record))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(positionText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(tempoText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            extraActions()
        }
    }
}
