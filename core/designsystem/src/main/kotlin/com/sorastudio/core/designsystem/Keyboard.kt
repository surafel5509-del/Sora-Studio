package com.sorastudio.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sorastudio.core.common.NoteUtils

/**
 * Playable piano keyboard with multi-touch and glissando.
 * [baseMidi] is the leftmost (white) key's note; [whiteKeyCount] controls range.
 */
@Composable
fun PianoKeyboard(
    baseMidi: Int,
    whiteKeyCount: Int = 14,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activeNotes: Set<Int> = emptySet(),
    inScale: ((Int) -> Boolean)? = null,
    accent: Color = SoraPalette.Accent
) {
    val darkSurface = Color(0xFF17181D)
    val pressedNotes = remember { mutableMapOf<PointerId, Int>() }
    var redraw by remember { mutableStateOf(0) }
    val outline = MaterialTheme.colorScheme.outline

    data class KeyRect(val midi: Int, val left: Float, val top: Float, val right: Float, val bottom: Float, val black: Boolean)

    fun buildKeys(width: Float, height: Float): List<KeyRect> {
        val whiteWidth = width / whiteKeyCount
        val keys = mutableListOf<KeyRect>()
        var midi = baseMidi
        var whiteIndex = 0
        // Ensure base is a white key
        while (NoteUtils.isBlackKey(midi)) midi++
        while (whiteIndex < whiteKeyCount) {
            val left = whiteIndex * whiteWidth
            keys.add(KeyRect(midi, left, 0f, left + whiteWidth, height, false))
            // black key after this white key (except E and B)
            val pc = midi % 12
            if (pc != 4 && pc != 11 && whiteIndex < whiteKeyCount - 1) {
                val bw = whiteWidth * 0.62f
                keys.add(KeyRect(midi + 1, left + whiteWidth - bw / 2, 0f, left + whiteWidth + bw / 2, height * 0.62f, true))
            }
            midi++
            while (NoteUtils.isBlackKey(midi)) midi++
            whiteIndex++
        }
        return keys
    }

    fun noteAt(offset: Offset, keys: List<KeyRect>): Int? {
        // black keys take priority
        for (key in keys.filter { it.black }) {
            if (offset.x in key.left..key.right && offset.y in key.top..key.bottom) return key.midi
        }
        for (key in keys.filter { !it.black }) {
            if (offset.x in key.left..key.right && offset.y in key.top..key.bottom) return key.midi
        }
        return null
    }

    Canvas(
        modifier = modifier.pointerInput(baseMidi, whiteKeyCount) {
            val keys = buildKeys(size.width.toFloat(), size.height.toFloat())
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        val current = pressedNotes[change.id]
                        if (change.pressed) {
                            val note = noteAt(change.position, keys)
                            if (note != null && note != current) {
                                if (current != null) onNoteOff(current)
                                onNoteOn(note)
                                pressedNotes[change.id] = note
                                redraw++
                            }
                        } else if (current != null) {
                            onNoteOff(current)
                            pressedNotes.remove(change.id)
                            redraw++
                        }
                    }
                }
            }
        }
    ) {
        @Suppress("UNUSED_EXPRESSION") redraw
        val keys = buildKeys(size.width, size.height)
        val pressed = pressedNotes.values.toSet() + activeNotes
        val whiteColor = if (darkSurface.luminanceApprox() < 0.5f) Color(0xFFEDEDF0) else Color.White
        for (key in keys.filter { !it.black }) {
            val isPressed = key.midi in pressed
            val outOfScale = inScale != null && !inScale(key.midi)
            val fill = when {
                isPressed -> accent
                outOfScale -> Color(0xFFC9CAD1)
                else -> whiteColor
            }
            drawRect(fill, topLeft = Offset(key.left, key.top), size = Size(key.right - key.left, key.bottom - key.top))
            drawRect(outline, topLeft = Offset(key.left, key.top), size = Size(key.right - key.left, key.bottom - key.top), style = Stroke(0.75.dp.toPx()))
        }
        for (key in keys.filter { it.black }) {
            val isPressed = key.midi in pressed
            val outOfScale = inScale != null && !inScale(key.midi)
            val fill = when {
                isPressed -> accent
                outOfScale -> Color(0xFF3A3D44)
                else -> darkSurface
            }
            drawRect(fill, topLeft = Offset(key.left, key.top), size = Size(key.right - key.left, key.bottom - key.top))
            drawRect(outline, topLeft = Offset(key.left, key.top), size = Size(key.right - key.left, key.bottom - key.top), style = Stroke(0.75.dp.toPx()))
        }
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
