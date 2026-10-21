package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorSmoothing - класс для устранения стробоскопического эффекта при обновлении LED.
 */
class ColorSmoothing(private val mDataSender: LedDataSender?) {

    // Значения по умолчанию
    companion object {
        private const val TAG = "ColorSmoothing"
        private const val DEBUG = false
        private const val DEFAULT_UPDATE_FREQUENCY_HZ = 60
        private const val DEFAULT_SETTLING_TIME_MS = 50
        private const val DEFAULT_OUTPUT_DELAY_MS = 0L

        // Пауза между повторами сошедшегося кадра. Совсем замолчать нельзя: часть прошивок
        // по таймауту тишины уходит в собственный эффект, у WLED это staytime ~10 секунд.
        private const val IDLE_RESEND_MS = 1000L
    }

    // Конфигурация
    @Volatile
    private var mUpdateFrequencyHz = DEFAULT_UPDATE_FREQUENCY_HZ
    @Volatile
    private var mSettlingTimeMs = DEFAULT_SETTLING_TIME_MS

    // Меняется на ходу из потока настроек, читается циклом отправки
    @Volatile
    private var mOutputDelayMs: Long = DEFAULT_OUTPUT_DELAY_MS
    @Volatile
    private var mEnabled = true

    // Состояние
    private var mInterpolator = LedColorInterpolator()
    private var mHasTarget = false
    @Volatile
    private var mNextTickNs = 0L

    // Кадр после схождения интерполяции уже отправлен: дальше цикл шлёт только редкие
    // повторы. Гнать одинаковые кадры на полной частоте нельзя — сплошной поток без пауз
    // переполняет приёмник Adalight, протокол рассинхронизируется, и лента мигает даже
    // на статичной картинке (issue #42).
    private var mIdleFrameSent = false

    @Volatile
    private var mLastSendMs = 0L

    // Очередь вывода (Output Delay) - хранит пары (время добавления, кадр)
    private data class TimedFrame(val timestamp: Long, val frame: OutputFrame)
    private data class OutputFrame(val colors: Array<ColorRgb>, val precise: FloatArray, val generation: Int)

    private val mOutputQueue = ArrayDeque<TimedFrame>()

    // Таймер
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: Handler? = null

    @Volatile
    private var mRunning = false

    // Поколение цикла: доигрывающий тик остановленного цикла иначе перепощивал бы себя в
    // очередь нового HandlerThread'а параллельно с постом из start() — две цепочки тиков
    // дают двойную частоту отправки навсегда
    @Volatile
    private var mGeneration = 0

    fun interface LedDataSender {
        fun sendLedData(colors: Array<ColorRgb>)

        /** Дробные RGB-каналы в диапазоне 0–255; получатель не должен изменять массивы. */
        fun sendPreciseLedData(colors: Array<ColorRgb>, precise: FloatArray) {
            sendLedData(colors)
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Меняет алгоритм с очисткой кадров, подготовленных старым алгоритмом. */
    @Synchronized
    fun setAlgorithm(algorithm: String, luminanceLimit: Int = 0) {
        mInterpolator = LedColorInterpolator(algorithm, luminanceLimit.coerceIn(0, 1000) * 2.55f)
        mHasTarget = false
        mIdleFrameSent = false
        synchronized(mOutputQueue) { mOutputQueue.clear() }
    }

    private val mUpdateRunnable = object : Runnable {
        override fun run() {
            val generation = mGeneration
            if (!mRunning || !needsTimer()) return

            updateLeds()

            if (mRunning && generation == mGeneration && mHandler != null) {
                val intervalNs = 1_000_000_000L / mUpdateFrequencyHz
                val now = System.nanoTime()
                // Дробная часть периода не теряется: 60 Гц не превращаются в 62,5 Гц.
                mNextTickNs += intervalNs
                if (mNextTickNs <= now) mNextTickNs = now + intervalNs
                mHandler?.postDelayed(this, (mNextTickNs - now + 999_999L) / 1_000_000L)
            }
        }
    }

    fun setTargetColors(targetColors: Array<ColorRgb>?) {
        if (targetColors.isNullOrEmpty()) return
        val direct: OutputFrame?
        synchronized(this) {
            val changed = mInterpolator.setTarget(targetColors, nowMs(), if (mEnabled) mSettlingTimeMs else 0)
            mHasTarget = true
            if (changed) mIdleFrameSent = false
            if (needsTimer()) {
                start()
                direct = null
            } else {
                direct = snapshot(nowMs())
            }
        }
        if (direct != null) sendToDevice(direct)
    }

    private fun snapshot(now: Long): OutputFrame {
        val precise = mInterpolator.snapshot(now)
        val colors = Array(precise.size / 3) { index ->
            val offset = index * 3
            ColorRgb(
                precise[offset].roundToInt().coerceIn(0, 255),
                precise[offset + 1].roundToInt().coerceIn(0, 255),
                precise[offset + 2].roundToInt().coerceIn(0, 255),
            )
        }
        return OutputFrame(colors, precise, mGeneration)
    }

    private fun updateLeds() {
        val now = nowMs()
        val frame = synchronized(this) {
            if (!mHasTarget || (mInterpolator.settled && mIdleFrameSent && now - mLastSendMs < IDLE_RESEND_MS)) {
                null
            } else {
                snapshot(now).also { if (mInterpolator.settled) mIdleFrameSent = true }
            }
        }
        if (frame != null) {
            queueColors(frame, now)
        } else if (mOutputDelayMs != 0L) {
            drainOutputQueue(now)
        }
    }

    private fun queueColors(frame: OutputFrame, now: Long) {
        if (mOutputDelayMs == 0L) {
            sendToDevice(frame)
        } else {
            synchronized(mOutputQueue) { mOutputQueue.addLast(TimedFrame(now, frame)) }
            drainOutputQueue(now)
        }
    }

    /**
     * Отдаёт все кадры, отлежавшие свою задержку. Под блокировкой только работа с очередью.
     * Отправка — снаружи: внутри неё блокирующая запись в порт или сокет, а этот же монитор
     * берёт stop() с главного потока при засыпании ТВ — держать его на время I/O нельзя.
     */
    private fun drainOutputQueue(now: Long) {
        val ready = ArrayList<TimedFrame>(2)
        synchronized(mOutputQueue) {
            while (mOutputQueue.isNotEmpty()) {
                val oldestFrame = mOutputQueue.first
                if (now - oldestFrame.timestamp >= mOutputDelayMs) {
                    ready.add(mOutputQueue.removeFirst())
                } else {
                    break
                }
            }
        }
        for (frame in ready) {
            sendToDevice(frame.frame)
        }
    }

    private fun sendToDevice(frame: OutputFrame) {
        if (frame.generation != mGeneration) return
        mLastSendMs = nowMs()
        mDataSender?.sendPreciseLedData(frame.colors, frame.precise)
    }

    // start/stop синхронизированы: start() зовут и поток отправки кадров, и поток
    // переподключения WLED — без блокировки два одновременных вызова создали бы по
    // HandlerThread, и первый из них остался бы жить навсегда (stop() видит только
    // последний mHandler).
    @Synchronized
    fun start() {
        if (mRunning) return

        mGeneration++
        val thread = HandlerThread("ColorSmoothing", Process.THREAD_PRIORITY_BACKGROUND)
        mHandlerThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        mHandler = handler

        mRunning = true
        val intervalNs = 1_000_000_000L / mUpdateFrequencyHz
        mNextTickNs = System.nanoTime() + intervalNs
        handler.postDelayed(mUpdateRunnable, (intervalNs + 999_999L) / 1_000_000L)
    }

    @Synchronized
    fun stop() {
        mRunning = false
        mGeneration++
        mHandler?.removeCallbacksAndMessages(null)
        mHandler = null
        mHandlerThread?.quitSafely()
        mHandlerThread = null
        synchronized(mOutputQueue) {
            mOutputQueue.clear()
        }
        mInterpolator.clear()
        mHasTarget = false
        mIdleFrameSent = false
    }

    @Synchronized
    fun setSettlingTime(ms: Int) {
        mSettlingTimeMs = max(0, min(1000, ms))
    }

    @Synchronized
    fun setOutputDelay(ms: Long) {
        val neededBefore = needsTimer()
        mOutputDelayMs = max(0L, min(1000L, ms))
        // Без задержки очередь больше не разбирается, и застрявшие в ней кадры вспыхнули бы
        // разом при следующем включении задержки
        if (mOutputDelayMs == 0L) synchronized(mOutputQueue) { mOutputQueue.clear() }
        onTimerNeedChanged(neededBefore)
    }

    @Synchronized
    fun setUpdateFrequency(hz: Int) {
        // Перепощивать runnable отсюда нельзя: auto-throttle Adalight зовёт этот метод из
        // самого цикла отправки, removeCallbacksAndMessages выполняющийся runnable не
        // снимает, и в очереди оказались бы две цепочки тиков — двойная частота навсегда.
        // Цикл сам читает mUpdateFrequencyHz на каждом тике и подхватит новое значение.
        mUpdateFrequencyHz = max(1, min(60, hz))
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val neededBefore = needsTimer()
        mEnabled = enabled
        onTimerNeedChanged(neededBefore)
    }

    /**
     * Цикл с таймером нужен не только сглаживанию: задержка вывода держит кадры в очереди,
     * и без цикла выключенное сглаживание отправляло бы их сразу, молча игнорируя задержку.
     * Без интерполяции цикл просто отдаёт цель на первом же тике.
     */
    private fun needsTimer(): Boolean = mEnabled || mOutputDelayMs > 0

    private fun onTimerNeedChanged(neededBefore: Boolean) {
        val needed = needsTimer()
        if (neededBefore && !needed && mRunning) {
            stop()
        } else if (needed && !neededBefore && mHasTarget && !mRunning) {
            start()
        }
    }

    fun isEnabled(): Boolean {
        return mEnabled
    }

    /**
     * Применяет пресет сглаживания
     * @param preset «off», «responsive», «balanced» или «smooth»
     */
    @Synchronized
    fun applyPreset(preset: String) {
        when (preset.lowercase()) {
            "off" -> {
                mEnabled = false
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "responsive" -> {
                mEnabled = true
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "balanced" -> {
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }

            "smooth" -> {
                mEnabled = true
                mSettlingTimeMs = 500
                mOutputDelayMs = 200L // ~5 кадров при 25 FPS
                mUpdateFrequencyHz = 20
            }

            else -> {
                // По умолчанию "balanced"
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }
        }
        // Выключенный пресет должен погасить и цикл: иначе mRunning остаётся true, и
        // последующий setEnabled(true) не смог бы его перезапустить.
        if (!needsTimer() && mRunning) stop()
        // Новый интервал цикл подхватит сам на следующем тике (см. setUpdateFrequency).
    }
}
