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
 * Аппаратный захват MStar через VSS/DIP с правами root.
 *
 * Нативный процесс передаёт кадры: ширина и высота (u32, little-endian),
 * затем пиксели RGB24. Вызовы драйвера не выполняются в главном потоке Android.
 * ADB и MediaProjection не используются, кадры не записываются на диск.
 * Нативный процесс завершается после трёх последовательных ошибок захвата
 * либо при обнаружении выхода записи за границы буфера.
 */
class MStarVssCaptureEncoder(
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
        val thread = HandlerThread("MStar-VSS", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        worker = thread
        handler = Handler(thread.looper).also { it.post { runCapture(currentSession) } }
    }

    private fun params(): List<String> {
        // Физическое разрешение панели может отличаться от разрешения интерфейса Android (1920×1080).
        fun panelSize(property: String): Pair<Int, Int>? {
            val value = try {
                val systemProperties = Class.forName("android.os.SystemProperties")
                val get = systemProperties.getMethod("get", String::class.java)
                get.invoke(null, property) as? String
            } catch (_: Exception) { null } ?: return null
            val parts = value.trim().lowercase().split("x")
            if (parts.size != 2) return null
            val w = parts[0].trim().toIntOrNull() ?: return null
            val h = parts[1].trim().toIntOrNull() ?: return null
            return if (w in 1..16384 && h in 1..16384) w to h else null
        }
        val display = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val mode = display.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        val (sw, sh) = panelSize("vendor.display-size")
            ?: panelSize("sys.display-size")
            ?: ((mode?.physicalWidth ?: 1920).coerceAtLeast(1) to
                (mode?.physicalHeight ?: 1080).coerceAtLeast(1))
        val width = options.captureQuality.coerceAtLeast(1)
        val ow = width.coerceAtMost(sw)
        val oh = (sh.toLong() * ow / sw).toInt().coerceAtLeast(1)
        return listOf("0", "0", sw.toString(), sh.toString(), ow.toString(), oh.toString(),
            options.frameRate.coerceAtLeast(1).toString())
    }

    private fun binary(): File {
        val destination = File(context.filesDir, BINARY)
        // Перед каждым запуском заменяем бинарник, чтобы не использовать старую версию после обновления APK.
        val zipPath = context.applicationInfo.sourceDir
        val abi = "armeabi-v7a" // Библиотеки производителя на этой платформе 32-разрядные
        java.util.zip.ZipFile(zipPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/lib$BINARY.so")
                ?: throw IOException("MStar native executable missing in APK")
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!destination.setExecutable(true, false)) throw IOException("Cannot chmod MStar native executable")
        return destination
    }

    private fun runCapture(expectedSession: Int) {
        var ownProcess: java.lang.Process? = null
        try {
            val args = params()
            val bin = binary()
            // Запускаем бинарник из каталога, проверенного диагностической утилитой.
            // Исполнение из закрытого каталога приложения может блокироваться noexec или SELinux.
            // Диагностический /data/local/tmp/mstar_probe не перезаписываем.
            // Устанавливаем бинарник только при изменении содержимого, заменяя его атомарно.
            val target = "/data/local/tmp/mstar_vss_server"
            val command = "if ! cmp -s '${bin.absolutePath}' '$target'; then " +
                "cp '${bin.absolutePath}' '${target}.new' && " +
                "chmod 700 '${target}.new' && mv -f '${target}.new' '$target' || exit 1; fi; " +
                "LD_LIBRARY_PATH=/vendor/lib:/system/lib exec '$target' ${args.joinToString(" ")}"
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
            }, "MStar-stderr").apply { isDaemon = true }.start()

            val input = DataInputStream(proc.inputStream)
            val header = ByteArray(8)
            while (running && session == expectedSession) {
                input.readFully(header)
                val width = leInt(header, 0)
                val height = leInt(header, 4)
                if (width <= 0 || height <= 0 || width.toLong() * height * 3L > Int.MAX_VALUE)
                    throw IOException("Invalid MStar frame header $width x $height")
                val rgb = ByteArray(width * height * 3)
                input.readFully(rgb)
                if (!running || session != expectedSession) break
                if (!capturing) { capturing = true; listener.sendStatus(true) }
                ColorProcessor.processRgbData(rgb, options)
                val crop = borders.applyForEncoder(rgb, width, height, options)
                listener.sendFrame(crop.rgb, crop.width, crop.height)
            }
            if (running && session == expectedSession) throw EOFException("MStar capture process stopped")
        } catch (e: Exception) {
            if (running && session == expectedSession) {
                Log.e(TAG, "MStar capture stopped", e)
                onFatalError?.invoke("MStar hardware capture stopped: ${e.message}")
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
        // Возобновление по явной команде или после ожидания; обработка ошибок ограничена нативным кодом.
        startWorker()
    }

    override fun setOrientation(orientation: Int) { /* Аппаратное масштабирование уже ориентировано по экрану. */ }

    companion object {
        private const val TAG = "MStarVssCapture"
        private const val BINARY = "mstar_vss_server"

        /** Быстрая проверка прошивки без вызовов su или ioctl с правами root в UI-потоке. */
        fun isAvailable(): Boolean =
            android.os.Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a") &&
            File("/vendor/lib/libmtal.so").exists()
    }
}
