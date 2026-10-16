package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * Root-powered MT5889 VSS/DIP hardware capture.
 *
 * Native process frames are (u32 width LE, u32 height LE, RGB24 payload).
 * No driver calls on Android main thread; no ADB, MediaProjection, or on-disk frames.
 * The native process exits after three consecutive CaptureOne errors or a guard failure.
 */
class Mt5889VssCaptureEncoder(
    private val context: Context,
    private val listener: HyperionThread.HyperionThreadListener,
    private val options: AppOptions,
    private val onFatalError: ((String) -> Unit)? = null,
) : CaptureBackend {
    @Volatile private var running = false
    @Volatile private var capturing = false
    @Volatile private var process: java.lang.Process? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var session = 0
    private val borders = BorderProcessor()

    init { startWorker() }

    @Synchronized
    private fun startWorker() {
        if (running && worker != null) return
        running = true
        capturing = false
        session++
        val currentSession = session
        val thread = HandlerThread("MT5889-VSS", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        worker = thread
        handler = Handler(thread.looper).also { it.post { runCapture(currentSession) } }
    }

    private fun params(): List<String> {
        // Use actual TV display mode rather than a fixed 3840x2160 coordinate region.
        val display = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val mode = display.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        val sw = (mode?.physicalWidth ?: 1920).coerceAtLeast(1)
        val sh = (mode?.physicalHeight ?: 1080).coerceAtLeast(1)
        val width = options.captureQuality.coerceAtLeast(1)
        val ow = width.coerceAtMost(sw)
        val oh = (sh.toLong() * ow / sw).toInt().coerceAtLeast(1)
        return listOf("0", "0", sw.toString(), sh.toString(), ow.toString(), oh.toString(),
            options.frameRate.coerceAtLeast(1).toString())
    }

    private fun binary(): File {
        val destination = File(context.filesDir, BINARY)
        // Always replace on each process creation so installed updates don't reuse an old native binary.
        val zipPath = context.applicationInfo.sourceDir
        val abi = "armeabi-v7a" // MT5889 TV has a 32-bit vendor userspace
        java.util.zip.ZipFile(zipPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/lib$BINARY.so")
                ?: throw IOException("MT5889 native executable missing in APK")
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!destination.setExecutable(true, false)) throw IOException("Cannot chmod MT5889 native executable")
        return destination
    }

    private fun runCapture(expectedSession: Int) {
        var ownProcess: java.lang.Process? = null
        try {
            val args = params()
            val bin = binary()
            // Stage executable in the same location that worked for the diagnostic probe.
            // Android app-private data may be noexec / SELinux-blocked even when read by su.
            // Never overwrite the user's existing /data/local/tmp/mt5889_probe.
            val command = "cp ${bin.absolutePath} /data/local/tmp/mt5889_vss_server && " +
                "chmod 700 /data/local/tmp/mt5889_vss_server && " +
                "LD_LIBRARY_PATH=/vendor/lib:/system/lib exec /data/local/tmp/mt5889_vss_server ${args.joinToString(" ")}"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            ownProcess = proc
            if (!running || session != expectedSession) { proc.destroy(); return }
            process = proc
            Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) Log.w(TAG, "[VSS] $line")
                    }
                } catch (_: IOException) { }
            }, "MT5889-stderr").apply { isDaemon = true }.start()

            val input = DataInputStream(proc.inputStream)
            val header = ByteArray(8)
            while (running && session == expectedSession) {
                input.readFully(header)
                val width = leInt(header, 0)
                val height = leInt(header, 4)
                if (width <= 0 || height <= 0 || width.toLong() * height * 3L > Int.MAX_VALUE)
                    throw IOException("Invalid MT5889 frame header $width x $height")
                val rgb = ByteArray(width * height * 3)
                input.readFully(rgb)
                if (!running || session != expectedSession) break
                if (!capturing) { capturing = true; listener.sendStatus(true) }
                ColorProcessor.processRgbData(rgb, options)
                val crop = borders.applyForEncoder(rgb, width, height, options)
                listener.sendFrame(crop.rgb, crop.width, crop.height)
            }
            if (running && session == expectedSession) throw EOFException("MT5889 capture process stopped")
        } catch (e: Exception) {
            if (running && session == expectedSession) {
                Log.e(TAG, "MT5889 capture stopped", e)
                onFatalError?.invoke("MT5889 hardware capture stopped: ${e.message}")
            }
        } finally {
            ownProcess?.destroy()
            if (session == expectedSession) {
                capturing = false
                running = false
                if (process === ownProcess) process = null
                listener.sendStatus(false)
            }
        }
    }

    private fun leInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 255) or
        ((buffer[offset + 1].toInt() and 255) shl 8) or
        ((buffer[offset + 2].toInt() and 255) shl 16) or
        ((buffer[offset + 3].toInt() and 255) shl 24)

    override fun isCapturing(): Boolean = capturing
    override fun sendStatus() = listener.sendStatus(capturing)
    override fun clearLights() = listener.clear()

    @Synchronized
    override fun stopRecording() {
        session++
        running = false
        capturing = false
        process?.destroy()
        process = null
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
    }

    override fun resumeRecording() {
        // Explicit user or standby resume only; error recovery remains bounded in native code.
        startWorker()
    }

    override fun setOrientation(orientation: Int) { /* hardware scaler is screen-aligned */ }

    companion object {
        private const val TAG = "Mt5889VssCapture"
        private const val BINARY = "mt5889_vss_server"

        /** Synchronous, cheap firmware check only; no root ioctl or su command on UI thread. */
        fun isAvailable(): Boolean =
            android.os.Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a") &&
            File("/vendor/lib/libmtal.so").exists()
    }
}
