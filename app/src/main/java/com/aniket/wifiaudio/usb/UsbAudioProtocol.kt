package com.aniket.wifiaudio.usb

import java.io.DataInputStream
import java.io.DataOutputStream

object UsbAudioProtocol {
    const val PORT = 9876
    const val SYNC_REQUEST: Byte = 0x01
    const val SYNC_RESPONSE: Byte = 0x02
    const val AUDIO_FRAME: Byte = 0x03
    const val SESSION_START: Byte = 0x04
    const val SESSION_STOP: Byte = 0x05
    const val HEARTBEAT: Byte = 0x06

    data class Header(val type: Byte, val length: Int)

    fun writeMessage(out: DataOutputStream, type: Byte, payload: ByteArray = ByteArray(0)) {
        synchronized(out) {
            out.writeByte(type.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun readHeader(input: DataInputStream): Header {
        return Header(input.readByte(), input.readInt())
    }

    fun readPayload(input: DataInputStream, length: Int): ByteArray {
        require(length in 0..1_000_000) { "Invalid payload length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }
}
