package com.opdash.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.opdash.protocol.K1GPacketBuilder
import java.io.ByteArrayOutputStream

/**
 * Handles capturing a View (like a Google Map) and converting it into
 * fragmented UDP packets for the Tripper Dash.
 */
class MapCastingManager(
    private val onFragmentReady: (ByteArray) -> Unit
) {
    private var isCasting = false
    private val fragmentSize = 1000 // Max safe UDP payload for Dash

    fun startCasting(view: android.view.View? = null) {
        if (isCasting) return
        isCasting = true
        Thread {
            while (isCasting) {
                try {
                    val bitmap = if (view != null) {
                        captureView(view)
                    } else {
                        createDummyBitmap()
                    }
                    val jpeg = compressBitmap(bitmap)
                    sendFragments(jpeg)
                } catch (e: Exception) {
                    com.opdash.logging.Logger.d("Casting error: ${e.message}")
                }
                Thread.sleep(500) // 2 FPS to reduce lag
            }
        }.start()
    }

    fun stopCasting() {
        isCasting = false
    }

    private fun captureView(view: android.view.View): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun createDummyBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawText("MAP CASTING ACTIVE", 100f, 240f, paint)
        canvas.drawText("(Lag-Free Mode)", 120f, 300f, paint)
        return bitmap
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream) // 40% quality for speed
        return stream.toByteArray()
    }

    private fun sendFragments(data: ByteArray) {
        val totalFragments = (data.size + fragmentSize - 1) / fragmentSize
        for (i in 0 until totalFragments) {
            val start = i * fragmentSize
            val end = minOf(start + fragmentSize, data.size)
            val chunk = data.copyOfRange(start, end)
            
            val packet = K1GPacketBuilder.buildImagePacket(i, totalFragments, chunk)
            onFragmentReady(packet)
        }
    }
}