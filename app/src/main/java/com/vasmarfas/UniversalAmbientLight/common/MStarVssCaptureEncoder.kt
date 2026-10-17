package com.vasmarfas.UniversalAmbientLight.common

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var nativePid: AtomicInteger? = null
    @Volatile private var processStop: StopTicket? = null

    // Одна реальная очистка на экземпляр процесса; остальные вызывающие ждут её завершения.
    private class StopTicket {
        val started = AtomicBoolean(false)
        val finished = CountDownLatch(1)
    }
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
        // Сверяем извлечённый файл с APK: не перезаписываем бинарник без изменения версии.
        val zipPath = context.applicationInfo.sourceDir
        val abi = "armeabi-v7a" // Библиотеки производителя на этой платформе 32-разрядные
        java.util.zip.ZipFile(zipPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/lib$BINARY.so")
                ?: throw IOException("MStar native executable missing in APK")
            fun currentBinaryMatches(): Boolean {
                if (!destination.isFile || destination.length() != entry.size) return false
                val crc = java.util.zip.CRC32()
                destination.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val length = stream.read(buffer)
                        if (length < 0) break
                        crc.update(buffer, 0, length)
                    }
                }
                return crc.value == entry.crc
            }
            if (!currentBinaryMatches()) {
                val temporary = File(context.filesDir, "$BINARY.new")
                try {
                    zip.getInputStream(entry).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(destination))
                        throw IOException("Cannot update MStar native executable")
                } finally {
                    temporary.delete()
                }
            }
        }
        if (!destination.setExecutable(true, false)) throw IOException("Cannot chmod MStar native executable")
        return destination
    }

    // Запрос остановки не блокирует главный поток Android.
    private fun requestStop(proc: java.lang.Process) {
        try {
            proc.outputStream.write(1)
            proc.outputStream.flush()
        } catch (_: IOException) { /* Процесс мог уже завершиться. */ }
    }

    // После запроса остановки проверяем именно нативный PID, даже если su уже вышел.
    // Проверка /proc/<pid>/exe перед каждым сигналом защищает от повторного использования PID.
    // Состояние Z означает, что процесс уже не выполняется и не удерживает VSS.
    private fun ensureNativeStopped(pid: Int) {
        if (pid <= 1) {
            Log.w(TAG, "PID нативного VSS не получен, подтвердить его остановку невозможно")
            return
        }
        val command = "pid=$pid; " +
            "for attempt in 1 2 3 4 5; do " +
            "[ -r /proc/\$pid/stat ] || exit 0; " +
            "read -r stat < /proc/\$pid/stat || exit 0; " +
            "set -- \$stat; " +
            "case \"\$3\" in Z|X) exit 0;; esac; " +
            "exe=\$(readlink /proc/\$pid/exe 2>/dev/null); " +
            "case \"\$exe\" in /data/local/tmp/mstar_vss_server|" +
            "'/data/local/tmp/mstar_vss_server (deleted)') ;; " +
            "*) echo \"VSS PID identity mismatch\" >&2; exit 3;; esac; " +
            "case \$attempt in 2) kill -TERM \$pid;; 4) kill -KILL \$pid;; esac; " +
            "sleep 0.1; " +
            "done; " +
            "echo \"VSS native process did not exit\" >&2; exit 4"
        try {
            val check = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            if (!check.waitFor(2500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Таймаут подтверждения завершения VSS PID=$pid")
                check.destroyForcibly()
            } else if (check.exitValue() != 0) {
                Log.w(TAG, "Не удалось подтвердить остановку VSS PID=$pid, код=" + check.exitValue())
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Ожидание завершения VSS прервано, PID=$pid")
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки завершения VSS PID=$pid", e)
        }
    }

    // Не запускаем вторую очистку из finally: она дожидается первой, прежде чем
    // освобождать captureLock и разрешать новую аппаратную сессию.
    private fun stopProcess(proc: java.lang.Process, pidHolder: AtomicInteger?, stop: StopTicket) {
        if (!stop.started.compareAndSet(false, true)) {
            try {
                stop.finished.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Ожидание завершения очистки VSS прервано", e)
            }
            return
        }
        try {
            finishProcess(proc, pidHolder)
        } finally {
            stop.finished.countDown()
        }
    }

    // Полная остановка выполняется единственный раз, вне главного потока Android.
    private fun finishProcess(proc: java.lang.Process, pidHolder: AtomicInteger?) {
        Log.i(TAG, "Остановка MStar VSS, PID=" + (pidHolder?.get() ?: -1))
        requestStop(proc)
        try {
            // Сначала ждём штатного закрытия канала. Даже если su уже завершился,
            // ниже всё равно проверяем PID нативного сервера отдельно.
            proc.waitFor(250, TimeUnit.MILLISECONDS)
            ensureNativeStopped(pidHolder?.get() ?: -1)
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            proc.destroy()
        } finally {
            try { proc.outputStream.close() } catch (_: IOException) { }
        }
    }

    private fun runCapture(expectedSession: Int) {
        // Один аппаратный VSS-поток на экземпляр приложения, включая завершение
        // старой сессии перед стартом следующей.
        captureLock.lock()
        var ownProcess: java.lang.Process? = null
        var ownPid: AtomicInteger? = null
        var ownStop: StopTicket? = null
        try {
            if (!running || session != expectedSession) return
            val args = params()
            val expectedWidth = args[4].toInt()
            val expectedHeight = args[5].toInt()
            val bin = binary()
            // Запускаем бинарник из каталога, проверенного диагностической утилитой.
            // Исполнение из закрытого каталога приложения может блокироваться noexec или SELinux.
            // Диагностический /data/local/tmp/mstar_probe не перезаписываем.
            // Сравниваем с установленным через cmp и только при изменении заменяем файл атомарно.
            // Работающий процесс продолжит использовать прежний inode до завершения.
            val target = "/data/local/tmp/mstar_vss_server"
            val command = "if ! cmp -s '${bin.absolutePath}' '$target'; then " +
                "cp '${bin.absolutePath}' '${target}.new' && " +
                "chmod 700 '${target}.new' && mv -f '${target}.new' '$target' || exit 1; fi; " +
                "LD_LIBRARY_PATH=/vendor/lib:/system/lib exec '$target' ${args.joinToString(" ")}"
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            ownProcess = proc
            val pidHolder = AtomicInteger(-1)
            ownPid = pidHolder
            val stop = StopTicket()
            ownStop = stop
            Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        if (line.startsWith("MSTAR_PID=")) {
                            line.removePrefix("MSTAR_PID=").toIntOrNull()?.let { pid ->
                                if (pid > 1) {
                                    pidHolder.set(pid)
                                    Log.i(TAG, "MStar VSS native PID=$pid")
                                }
                            }
                        } else if (line.isNotBlank()) Log.w(TAG, "[VSS] $line")
                    }
                } catch (_: IOException) { }
            }, "MStar-stderr").apply { isDaemon = true }.start()
            // Регистрация под тем же монитором, что и stopRecording:
            // исключает гонку между проверкой сессии и сохранением процесса.
            val registered = synchronized(this) {
                if (!running || session != expectedSession) false
                else {
                    process = proc
                    nativePid = pidHolder
                    processStop = stop
                    true
                }
            }
            if (!registered) {
                // finally сам выполнит остановку; отдельный вызов дал бы дубль.
                return
            }

            val input = DataInputStream(proc.inputStream)
            val header = ByteArray(8)
            var rgbBuffer: ByteArray? = null
            while (running && session == expectedSession) {
                input.readFully(header)
                val width = leInt(header, 0)
                val height = leInt(header, 4)
                // Принимаем только запрошенную геометрию, чтобы повреждённый заголовок
                // не привёл к выделению огромного массива или рассинхронизации потока.
                if (width != expectedWidth || height != expectedHeight ||
                    width.toLong() * height * 3L > Int.MAX_VALUE)
                    throw IOException("Invalid MStar frame header $width x $height (expected $expectedWidth x $expectedHeight)")
                // UAL синхронно копирует кадр в HyperionThread или FrameDelayLine.
                // Поэтому после возврата sendFrame исходный RGB-массив можно
                // безопасно перезаписать следующим кадром без новой аллокации.
                val rgbSize = width * height * 3
                var rgb = rgbBuffer
                if (rgb == null || rgb.size != rgbSize) {
                    rgb = ByteArray(rgbSize)
                    rgbBuffer = rgb
                    Log.i(TAG, "RGB-буфер MStar выделен: $width x $height, $rgbSize байт")
                }
                input.readFully(rgb)
                if (!running || session != expectedSession) break
                ColorProcessor.processRgbData(rgb, options)
                val crop = borders.applyForEncoder(rgb, width, height, options)
                listener.sendFrame(crop.rgb, crop.width, crop.height)
                // Уведомляем UAL только после успешно отправленного первого кадра.
                if (!capturing) { capturing = true; listener.sendStatus(true) }
            }
            if (running && session == expectedSession) throw EOFException("MStar capture process stopped")
        } catch (e: Exception) {
            if (running && session == expectedSession) {
                Log.e(TAG, "MStar capture stopped", e)
                onFatalError?.invoke("MStar hardware capture stopped: ${e.message}")
            }
        } finally {
            // Дожидаемся единственной очистки, включая асинхронную из stopRecording.
            val ticket = ownStop
            if (ticket != null) ownProcess?.let { stopProcess(it, ownPid, ticket) }
            if (session == expectedSession) {
                capturing = false
                running = false
                if (process === ownProcess) { process = null; nativePid = null; processStop = null }
                listener.sendStatus(false)
            }
            captureLock.unlock()
        }
    }

    private fun leInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 255) or
        ((buffer[offset + 1].toInt() and 255) shl 8) or
        ((buffer[offset + 2].toInt() and 255) shl 16) or
        ((buffer[offset + 3].toInt() and 255) shl 24)

    /** Захват запускается, но первый кадр ещё не получен. */
    fun isStarting(): Boolean = running && !capturing

    override fun isCapturing(): Boolean = capturing
    override fun sendStatus() = listener.sendStatus(capturing)
    override fun clearLights() = listener.clear()

    @Synchronized
    override fun stopRecording() {
        session++
        running = false
        capturing = false
        val oldProcess = process
        val oldPid = nativePid
        val oldStop = processStop
        process = null
        nativePid = null
        processStop = null
        if (oldProcess != null && oldStop != null) {
            Thread({ stopProcess(oldProcess, oldPid, oldStop) }, "MStar-stop").apply {
                isDaemon = true
            }.start()
        }
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
        private val captureLock = java.util.concurrent.locks.ReentrantLock(true)

        /** Быстрая проверка прошивки без вызовов su или ioctl с правами root в UI-потоке. */
        fun isAvailable(): Boolean =
            android.os.Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a") &&
            File("/vendor/lib/libmtal.so").exists()
    }
}
