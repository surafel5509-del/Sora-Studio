package com.sorastudio.core.audio

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * External MIDI keyboard input via the Android MIDI API (USB/BLE MIDI).
 * Entirely optional: devices without the MIDI feature simply report none.
 */
class MidiDeviceManager(context: Context) {

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var device: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null

    var onNoteOn: ((pitch: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((pitch: Int) -> Unit)? = null

    fun availableDeviceNames(): List<String> {
        val manager = midiManager ?: return emptyList()
        return try {
            manager.devices
                .filter { it.inputPortCount > 0 || it.outputPortCount > 0 }
                .map { info ->
                    val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                    val manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
                    listOfNotNull(manufacturer, name).joinToString(" ").ifBlank { "MIDI Device" }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Connect to the first device that can send notes to the app (an output port from the device's perspective). */
    fun connectFirst(): Boolean {
        val manager = midiManager ?: return false
        disconnect()
        return try {
            val info = manager.devices.firstOrNull { it.outputPortCount > 0 } ?: return false
            var success = false
            manager.openDevice(info, { opened ->
                if (opened != null) {
                    device = opened
                    val port = opened.openOutputPort(0)
                    if (port != null) {
                        port.connect(parser)
                        outputPort = port
                        success = true
                    }
                }
            }, Handler(Looper.getMainLooper()))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        runCatching { outputPort?.close() }
        runCatching { device?.close() }
        outputPort = null
        device = null
    }

    private val parser = object : MidiReceiver() {
        private var runningStatus = 0
        private val pending = IntArray(2)
        private var pendingCount = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val byte = msg[i].toInt() and 0xFF
                if (byte and 0x80 != 0) {
                    runningStatus = byte
                    pendingCount = 0
                    i++
                    continue
                }
                if (runningStatus == 0) { i++; continue }
                when (runningStatus and 0xF0) {
                    0x90, 0x80, 0xA0, 0xB0, 0xE0 -> {
                        pending[pendingCount++] = byte
                        if (pendingCount == 2) {
                            handleChannelMessage(runningStatus, pending[0], pending[1])
                            pendingCount = 0
                        }
                    }
                    0xC0, 0xD0 -> {
                        // single data byte messages: ignore
                    }
                    else -> Unit
                }
                i++
            }
        }

        private fun handleChannelMessage(status: Int, data1: Int, data2: Int) {
            when (status and 0xF0) {
                0x90 -> if (data2 == 0) onNoteOff?.invoke(data1) else onNoteOn?.invoke(data1, data2)
                0x80 -> onNoteOff?.invoke(data1)
            }
        }
    }
}
