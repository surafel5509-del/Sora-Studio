package com.sorastudio.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Waveform from precomputed peaks ([min,max] pairs). Tapping seeks if [onSeekFraction] set.
 * [progress] 0..1 colours the played portion.
 */
@Composable
fun WaveformCanvas(
    peaks: FloatArray,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    color: Color = SoraPalette.AccentSecondary,
    playedColor: Color = SoraPalette.Accent,
    onSeekFraction: ((Float) -> Unit)? = null
) {
    val centreLine = MaterialTheme.colorScheme.outline
    val seekModifier = if (onSeekFraction != null) {
        Modifier.pointerInput(peaks) {
            detectTapGestures { offset ->
                onSeekFraction((offset.x / size.width).coerceIn(0f, 1f))
            }
        }
    } else Modifier
    Canvas(modifier = modifier.then(seekModifier)) {
        val midY = size.height / 2
        drawLine(centreLine.copy(alpha = 0.5f), Offset(0f, midY), Offset(size.width, midY), 1.dp.toPx())
        if (peaks.size < 2) return@Canvas
        val columns = peaks.size / 2
        val colWidth = size.width / columns
        val gap = if (colWidth > 3.dp.toPx()) 1.dp.toPx() * 0.4f else 0f
        val progressX = size.width * progress.coerceIn(0f, 1f)
        for (c in 0 until columns) {
            val mn = peaks[c * 2].coerceIn(-1f, 1f)
            val mx = peaks[c * 2 + 1].coerceIn(-1f, 1f)
            val x = c * colWidth + gap / 2
            val w = (colWidth - gap).coerceAtLeast(1f)
            val top = midY - mx * midY * 0.95f
            val bottom = midY - mn * midY * 0.95f
            val colColor = if (x < progressX) playedColor else color
            val h = (bottom - top).coerceAtLeast(1.5f)
            drawRect(colColor, topLeft = Offset(x, top), size = androidx.compose.ui.geometry.Size(w, h))
        }
        if (progress > 0f) {
            drawLine(playedColor, Offset(progressX, 0f), Offset(progressX, size.height), 1.5.dp.toPx())
        }
    }
}

/** Computes display peaks for a stereo buffer chunk (delegates to DSP). */
fun waveformPeaksFor(stereo: FloatArray, columns: Int = 240): FloatArray =
    com.sorastudio.core.dsp.WaveformPeaks.computeStereoPeaks(stereo, columns)

fun waveformPeaksForMono(mono: FloatArray, columns: Int = 240): FloatArray =
    com.sorastudio.core.dsp.WaveformPeaks.computeMonoPeaks(mono, columns)
