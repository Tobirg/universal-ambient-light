package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialProberFactory
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlin.math.max

class AdalightClient(
    private val mContext: Context,
    private val mPriority: Int,
    baudRate: Int,
    protocol: String = "ada",
    smoothingEnabled: Boolean = true,
    smoothingPreset: String = "balanced",
    settlingTime: Int = 200,
    outputDelayMs: Long = 80L,
    updateFrequency: Int = 25,
    colorMode: String = "rgb",
    rgbwCalibration: RgbwCalibration = RgbwCalibration(),
    smoothingAlgorithm: String = "linear",
    smoothingLuminanceLimit: Int = 0,
) : HyperionClient, StreamingClient, AddressableLedClient {

    enum class ProtocolType {
        ADA,    // Standard Adalight
        LBAPA,  // LightBerry APA102
        AWA,    // Hyperserial
        TPM2,
        SKYDIMO,
        SEDU,
        KARATE,
        ATMO,
        SP616E
    }

    private val mBaudRate: Int = if (baudRate > 0) baudRate else 115200
    private val mProtocol = when (protocol.lowercase()) {
        "lbapa", "1" -> ProtocolType.LBAPA
        "awa", "2", "awa-rgbw" -> ProtocolType.AWA
        "tpm2" -> ProtocolType.TPM2
        "skydimo" -> ProtocolType.SKYDIMO
        "sedu" -> ProtocolType.SEDU
        "karate" -> ProtocolType.KARATE
        "atmo" -> ProtocolType.ATMO
        "sp616e" -> ProtocolType.SP616E
        else -> ProtocolType.ADA
    }

    private val mIceRgbw = InfiniteColorEngineRgbw(
        rgbwCalibration.whiteThreshold, rgbwCalibration.whiteIntensity,
        floatArrayOf(rgbwCalibration.whiteRed, rgbwCalibration.whiteGreen, rgbwCalibration.whiteBlue),
    )
    // Два буфера под mWriteLock: keepalive сохраняет последний успешно отправленный кадр.
    private var mIceWriteBuffer = ByteArray(0)
    private var mIceSentBuffer = ByteArray(0)
    private val mColorMode = HyperSerialColorMode.fromPreference(
        if (protocol.equals("awa-rgbw", ignoreCase = true)) "rgbw" else colorMode
    )

    private var mPort: UsbSerialPort? = null

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    private val mLayoutTest = LedLayoutTestController(mContext) { leds -> sendLedData(leds) }

    override fun setLayoutTestEnabled(enabled: Boolean) {
        if (enabled) mSmoothing.stop()
        mLayoutTest.setEnabled(enabled, mLastLeds?.size ?: 0)
        if (!enabled && isConnected()) mSmoothing.start()
    }

    private val mKeepAlive = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AdalightClient-keepalive").apply { isDaemon = true }
    }

    private val mWriteLock = Any()

    @Volatile
    private var mLastLeds: Array<ColorRgb>? = null

    // Последний фактически отправленный AWA-кадр для keepalive и корректного гашения.
    private var mLastAwaFrame: AdalightPacketEncoder.AwaFrame? = null

    @Volatile
    private var mLastSendMs = 0L

    // Сохраняем исходно запрошенную частоту, чтобы auto-throttle никогда не повышал её выше пользовательской
    private val mRequestedUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mEffectiveUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mLastAutoThrottlePacketSize: Int = -1

    init {
        mSmoothing = ColorSmoothing(object : ColorSmoothing.LedDataSender {
            override fun sendLedData(colors: Array<ColorRgb>) {
                synchronized(mLayoutTest) {
                    if (!mLayoutTest.enabled) this@AdalightClient.sendLedData(colors)
                }
            }

            override fun sendPreciseLedData(colors: Array<ColorRgb>, precise: FloatArray) {
                synchronized(mLayoutTest) {
                    if (!mLayoutTest.enabled) this@AdalightClient.sendLedData(colors, precise)
                }
            }
        })
        mSmoothing.setAlgorithm(smoothingAlgorithm, smoothingLuminanceLimit)
        // Применить настройки сглаживания из preferences
        // Сначала применяем пресет как базовые значения
        mSmoothing.applyPreset(smoothingPreset)
        // Затем переопределяем настройками из preferences только если они отличаются от значений пресета
        // Это позволяет пресету работать, но пользователь может переопределить настройки вручную
        val presetValues = getPresetValues(smoothingPreset)
        if (settlingTime != presetValues.settlingTime) {
            mSmoothing.setSettlingTime(settlingTime)
        }
        if (outputDelayMs != presetValues.outputDelayMs) {
            mSmoothing.setOutputDelay(outputDelayMs)
        }
        if (updateFrequency != presetValues.updateFrequency) {
            mSmoothing.setUpdateFrequency(updateFrequency)
            mEffectiveUpdateFrequency = updateFrequency
        } else {
            // Частота из пресета остаётся в силе
            mEffectiveUpdateFrequency = presetValues.updateFrequency
        }
        // enabled всегда переопределяем, так как это отдельная настройка
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        val usbManager = mContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB service not available on this device")

        // Ищем все доступные USB-устройства с последовательным портом
        val availableDrivers = UsbSerialProberFactory.getProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IOException("No USB serial devices found. Please connect your Adalight device via USB OTG cable")
        }

        // Пишем найденные устройства в лог для диагностики
        Log.d(TAG, "Found " + availableDrivers.size + " USB serial device(s)")
        for (i in availableDrivers.indices) {
            val dev = availableDrivers[i].device
            Log.d(
                TAG, "Device " + i + ": VID=" + dev.vendorId + " PID=" + dev.productId +
                        " Name=" + dev.deviceName
            )
        }

        // Предпочитаем устройство, для которого уже получено USB-разрешение.
        // Первое в списке может отличаться от устройства из USB_DEVICE_ATTACHED.
        // Если разрешений нет, сохраняем прежнее поведение и сообщение об ошибке.
        val driver = UsbSerialDeviceSelector.select(availableDrivers) {
            usbManager.hasPermission(it.device)
        }
        val device = driver.device

        // Проверяем разрешение.
        // На этом этапе диалог уже должен быть показан активити,
        // поэтому из сервиса мы только проверяем флаг.
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB device permission denied. Please allow USB access when prompted, or grant permission manually in Android Settings > Apps > Hyperion Grabber > Permissions")
        }

        // Открываем порт
        val ports = driver.ports
        if (ports.isEmpty()) {
            throw IOException("No serial ports available on USB device")
        }

        val port = ports[0]
        mPort = port

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device. Please check USB connection and try again")

        try {
            port.open(connection)
            port.setParameters(mBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Поднимаем DTR/RTS. Часть мостов (CP210x, ряд клонов CH340) молчит, пока линии не
            // подняты; на платах с авто-reset это ещё и перезагружает MCU, чтобы прошивка заново
            // отправила свой стартовый маркер "Ada" для хендшейка ниже. Best-effort: драйверы,
            // которые не поддерживают линию, кидают исключение — его игнорируем.
            if (mProtocol == ProtocolType.AWA) {
                initializeAwaPort(port, device.vendorId, device.productId)
            } else {
                try {
                    port.setDTR(true)
                } catch (_: Exception) {
                }
                try {
                    port.setRTS(true)
                } catch (_: Exception) {
                }
            }

            // Хендшейк по «магическому слову» Adalight: стандартная прошивка при старте печатает
            // "Ada\n" (см. adalight-sketch.ino и рекомендованный сторонним приложением скетч).
            // Ждём его, чтобы не начать слать кадры в устройство, которое ещё перезагружается —
            // именно из-за этого у нас были рассинхрон и мерцание на старте в отличие от аналога.
            // Если устройство не прислало "Ada" за таймаут — всё равно продолжаем (некоторые
            // прошивки маркер не шлют), поэтому совместимость не ухудшается.
            if (mProtocol == ProtocolType.ADA || mProtocol == ProtocolType.LBAPA) {
                waitForAdaHandshake()
            } else if (mProtocol == ProtocolType.AWA) {
                // HyperHDR определяет совместимые прошивки по стартовому сообщению.
                // Отсутствие сообщения не запрещает подключение совместимой прошивки.
                waitForAwaHandshake()
            }

            mConnected = true
            mSmoothing.start()
            mKeepAlive.scheduleWithFixedDelay({
                synchronized(mLayoutTest) {
                    mLayoutTest.refresh()
                    if (mPaused || !isConnected()) return@scheduleWithFixedDelay
                    if (System.currentTimeMillis() - mLastSendMs < KEEPALIVE_MS) return@scheduleWithFixedDelay
                    if (mProtocol == ProtocolType.AWA) {
                        synchronized(mWriteLock) write@{
                            val frame = mLastAwaFrame ?: return@write
                            val packet = AdalightPacketEncoder.createAwaPacket(frame)
                            val port = mPort ?: return@write
                            if (!mConnected || mPaused) return@write
                            try {
                                port.write(packet, 1000)
                                mLastSendMs = System.currentTimeMillis()
                            } catch (e: Exception) {
                                // USB-драйвер может выбросить непроверяемое исключение при отключении устройства.
                                Log.e(TAG, "HyperSerial keepalive failed", e)
                                mConnected = false
                            }
                        }
                    } else {
                        val last = mLastLeds ?: return@scheduleWithFixedDelay
                        sendLedData(Array(last.size) { last[it].clone() })
                    }
                }
            }, KEEPALIVE_MS, KEEPALIVE_MS / 2, TimeUnit.MILLISECONDS)
            Log.i(
                TAG, "Successfully connected to Adalight device at " + mBaudRate + " baud (VID=" +
                        device.vendorId + " PID=" + device.productId + ")"
            )
        } catch (e: Exception) {
            mConnected = false
            // Освобождаем USB-соединение до выхода с ошибкой: иначе устройство остаётся
            // «занятым», и повторные попытки подключения не пройдут до перезапуска приложения.
            try {
                mPort?.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
                // Освобождаем порт после неудачного открытия: часть ресурсов могла и не
                // создаться, но оставить занятым нельзя (см. комментарий выше).
            }
            mPort = null
            throw IOException(
                "Failed to configure USB serial port: " + e.message +
                        ". Try different baud rate or check device compatibility", e
            )
        }
    }

    /** Запуск AWA необязателен: неподдерживаемые линии управления не должны отклонять USB-устройство. */
    private fun initializeAwaPort(port: UsbSerialPort, vendorId: Int, productId: Int) {
        HyperSerialPortStartup.initialize(
            vendorId, productId,
            setLines = { dtr, rts ->
                try {
                    port.setDTR(dtr)
                } catch (e: Exception) {
                    Log.d(TAG, "AWA DTR unavailable: ${e.message}")
                }
                try {
                    port.setRTS(rts)
                } catch (e: Exception) {
                    Log.d(TAG, "AWA RTS unavailable: ${e.message}")
                }
            },
            sleep = { delay -> Thread.sleep(delay) },
        )
    }

    /**
     * Ждём стартовый маркер "Ada", который стандартная прошивка Adalight печатает при загрузке.
     * Возвращаемся сразу, как только маркер получен, либо по истечении таймаута (best-effort —
     * не блокируем устройства, которые маркер не шлют).
     */
    private fun waitForAdaHandshake() {
        val port = mPort ?: return
        val buf = ByteArray(64)
        val received = StringBuilder()
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = port.read(buf, HANDSHAKE_READ_TIMEOUT_MS)
                if (n > 0) {
                    received.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (received.contains(ADA_MAGIC_WORD)) {
                        Log.i(TAG, "Adalight handshake OK: device announced 'Ada'")
                        return
                    }
                    // Ограничиваем буфер: маркер короткий, хвоста достаточно для склейки на границе.
                    if (received.length > 128) received.delete(0, received.length - 8)
                }
            }
            Log.i(TAG, "No 'Ada' handshake within ${HANDSHAKE_TIMEOUT_MS}ms — proceeding anyway")
        } catch (e: Exception) {
            Log.w(TAG, "Handshake read failed (${e.message}) — proceeding anyway")
        }
    }

    /** Проверка HyperSerial необязательна: неизвестные совместимые прошивки не отклоняем. */
    private fun waitForAwaHandshake() {
        val port = mPort ?: return
        val buf = ByteArray(64)
        val received = StringBuilder()
        val deadline = System.currentTimeMillis() + AWA_HANDSHAKE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = port.read(buf, HANDSHAKE_READ_TIMEOUT_MS)
                if (n > 0) {
                    received.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (received.contains("Awa driver", ignoreCase = true)) {
                        Log.i(TAG, "HyperSerial firmware identified")
                        return
                    }
                    if (received.length > 128) received.delete(0, received.length - 64)
                }
            }
            Log.i(TAG, "No HyperSerial banner detected; continuing with AWA")
        } catch (e: Exception) {
            Log.w(TAG, "HyperSerial identification unavailable (${e.message}); continuing")
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mPort != null
    }

    override fun pauseSending() {
        mPaused = true
        mSmoothing.stop()
    }

    override fun resumeSending() {
        mPaused = false
    }

    override val delaysOutput = true

    override fun setOutputDelay(ms: Long) {
        mSmoothing.setOutputDelay(ms)
    }

    override fun setSmoothingEnabled(enabled: Boolean) {
        mSmoothing.setEnabled(enabled)
    }

    @Throws(IOException::class)
    override fun disconnect() {
        // stop() обрывает недоигранный фейд в чёрный (переход плюс задержка вывода могут
        // не уложиться в паузу перед disconnect), и лента осталась бы полугорящей —
        // финальный чёрный кадр уходит в порт напрямую, мимо сглаживания. Мы на фоновом
        // потоке гашения, блокирующая запись здесь допустима.
        mSmoothing.stop()
        mKeepAlive.shutdownNow()
        synchronized(mWriteLock) {
            val port = mPort
            if (mConnected && port != null) {
                try {
                    val lastFrame = if (mProtocol == ProtocolType.AWA) mLastAwaFrame else null
                    val blackPacket = when (lastFrame) {
                        is AdalightPacketEncoder.AwaFrame.Rgb ->
                            AdalightPacketEncoder.createAwaPacket(
                                Array(lastFrame.leds.size) { ColorRgb(0, 0, 0) },
                                lastFrame.calibration
                            )
                        is AdalightPacketEncoder.AwaFrame.Rgbw ->
                            AdalightPacketEncoder.createAwaRgbwPacket(ByteArray(lastFrame.channels.size))
                        null -> {
                            val ledCount = maxOf(LedDataExtractor.getLedCount(mContext), mLastLeds?.size ?: 0)
                            if (ledCount > 0) createPacket(
                                mProtocol, Array(ledCount) { ColorRgb(0, 0, 0) }
                            ) else null
                        }
                    }
                    if (blackPacket != null) port.write(blackPacket, 1000)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send final black frame", e)
                }
            }
            mConnected = false
            try {
                port?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing port", e)
            }
            mPort = null
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        if (mLayoutTest.enabled) return
        // Отправляем все светодиоды чёрными
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
        // Гашение проходит через штатное сглаживание; формат RGBW сохраняет отправитель.
        mSmoothing.setTargetColors(blackLeds)
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(mPriority)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (mLayoutTest.enabled) return
        // Число светодиодов берём из настроек
        val ledCount = LedDataExtractor.getLedCount(mContext)

        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val leds = Array(ledCount) { ColorRgb(r, g, b) }
        mSmoothing.setTargetColors(leds)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!isConnected()) {
            throw IOException("Not connected to Adalight device")
        }

        if (mLayoutTest.enabled) return

        val leds = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        mLedDataBuffer = leds
        if (leds.isEmpty()) return

        // Отдаём в сглаживание
        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    internal fun sendAwaFrame(frame: AdalightPacketEncoder.AwaFrame) {
        if (mProtocol != ProtocolType.AWA) {
            throw IllegalStateException("HyperSerial frames require AWA protocol")
        }
        if (!isConnected() || mPaused) return
        val packet = AdalightPacketEncoder.createAwaPacket(frame)
        // Синхронизируем сброс очереди с записью: уже выполняющийся callback
        // сглаживания не должен перезаписать RGBW-кадр после его отправки.
        synchronized(mWriteLock) {
            if (frame is AdalightPacketEncoder.AwaFrame.Rgbw) {
                mSmoothing.stop()
                if (!mPaused && mConnected) mSmoothing.start()
            }
            val port = mPort ?: return
            if (!mConnected || mPaused) return
            try {
                port.write(packet, 1000)
                mLastSendMs = System.currentTimeMillis()
                mLastLeds = null
                mLastAwaFrame = when (frame) {
                    is AdalightPacketEncoder.AwaFrame.Rgb ->
                        AdalightPacketEncoder.AwaFrame.Rgb(
                            Array(frame.leds.size) { frame.leds[it].clone() }, frame.calibration
                        )
                    is AdalightPacketEncoder.AwaFrame.Rgbw ->
                        AdalightPacketEncoder.AwaFrame.Rgbw(frame.channels.clone())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send HyperSerial frame", e)
                mConnected = false
            }
        }
    }

    // Обратный вызов от ColorSmoothing
    private fun sendLedData(leds: Array<ColorRgb>, precise: FloatArray? = null) {
        if (!isConnected() || mPaused) return

        val port = mPort
        if (port == null) {
            Log.w(TAG, "Port is null, connection lost")
            mConnected = false
            return
        }

        try {
            if (mProtocol != ProtocolType.AWA) {
                // Оценка предполагает блокирующий вывод WS2812 и UART 8N1.
                // HyperSerial может использовать буферизованный USB CDC и параллельный вывод LED.
                maybeAutoThrottle(createPacket(mProtocol, leds).size, leds.size)
            }
            synchronized(mWriteLock) {
                if (!mConnected || mPort !== port) return
                // После очистки сглаживание может дослать чёрный RGB-кадр:
                // не переключаем устройство с RGBW на RGB при таком переходе.
                val frame = if (mProtocol == ProtocolType.AWA) {
                    if (mColorMode == HyperSerialColorMode.RGBW ||
                        mColorMode == HyperSerialColorMode.ICE
                    ) {
                        val channels = if (mColorMode == HyperSerialColorMode.ICE) {
                            if (mIceWriteBuffer.size != leds.size * 4) {
                                mIceWriteBuffer = ByteArray(leds.size * 4)
                            }
                            mIceRgbw.convertInto(leds, mIceWriteBuffer, precise = precise)
                            mIceWriteBuffer
                        } else {
                            HyperSerialRgbwColor.convert(leds)
                        }
                        AdalightPacketEncoder.AwaFrame.Rgbw(channels)
                    } else {
                        AdalightPacketEncoder.frameForSmoothedRgb(leds, mLastAwaFrame)
                    }
                } else {
                    null
                }
                val packet = if (frame != null) {
                    AdalightPacketEncoder.createAwaPacket(frame)
                } else {
                    createPacket(mProtocol, leds)
                }
                port.write(packet, 1000)
                mLastLeds = Array(leds.size) { leds[it].clone() }
                if (frame is AdalightPacketEncoder.AwaFrame.Rgbw &&
                    mColorMode == HyperSerialColorMode.ICE
                ) {
                    val reusable = mIceSentBuffer
                    mIceSentBuffer = mIceWriteBuffer
                    mIceWriteBuffer = reusable
                }
                if (frame != null) mLastAwaFrame = frame
                mLastSendMs = System.currentTimeMillis()
            }

            // Изредка пишем в лог для диагностики
            if (System.currentTimeMillis() % 2000 < 50) {
                Log.v(TAG, "Sent packet: " + leds.size + " LEDs")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data", e)
            mConnected = false
        } catch (e: NullPointerException) {
            Log.e(TAG, "USB connection lost (NullPointerException)", e)
            mConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending data", e)
            mConnected = false
        }
    }

    /**
     * Auto-throttle частоту сглаживания под выбранный baudrate и фактический размер пакета.
     * Это резко повышает стабильность при большом количестве LED (иначе данные начинают идти "без пауз",
     * Arduino теряет байты, и протокол рассинхронизируется).
     */
    private fun maybeAutoThrottle(packetSizeBytes: Int, ledCount: Int) {
        if (packetSizeBytes <= 0) return
        // Пересчитываем только если меняется размер (например, при смене протокола или количества LED)
        if (packetSizeBytes == mLastAutoThrottlePacketSize) return
        mLastAutoThrottlePacketSize = packetSizeBytes

        // Приблизительно: 1 байт = 10 бит (8N1)
        val wireMs = packetSizeBytes * 10_000.0 / mBaudRate
        // Пока прошивка переливает кадр в ленту, приём стоит: WS2812 требует держать
        // прерывания выключенными ~30 мкс на светодиод, и байты следующего пакета,
        // пришедшие в это окно, теряются. Резервируем окно явно, с запасом на цикл прошивки.
        val showMs = ledCount * 0.03 + 2.0
        val safeHz = (1000.0 / (wireMs + showMs)).toInt().coerceIn(1, 60)

        val desiredHz = minOf(mRequestedUpdateFrequency, safeHz)
        if (desiredHz != mEffectiveUpdateFrequency) {
            mEffectiveUpdateFrequency = desiredHz
            mSmoothing.setUpdateFrequency(desiredHz)
            Log.i(
                TAG,
                "Auto-throttle smoothing: ${desiredHz}Hz (baud=$mBaudRate, packet=$packetSizeBytes bytes)"
            )
        }
    }

    private fun createPacket(protocol: ProtocolType, leds: Array<ColorRgb>): ByteArray {
        return when (protocol) {
            ProtocolType.ADA -> AdalightPacketEncoder.createAdaPacket(leds)
            ProtocolType.LBAPA -> createLbapaPacket(leds)
            ProtocolType.AWA -> AdalightPacketEncoder.createAwaPacket(leds)
            ProtocolType.TPM2 -> createTpm2Packet(leds)
            ProtocolType.SKYDIMO -> createSkydimoPacket(leds)
            ProtocolType.SEDU -> createSeduPacket(leds)
            ProtocolType.KARATE -> createKaratePacket(leds)
            ProtocolType.ATMO -> createAtmoPacket(leds)
            ProtocolType.SP616E -> createSp616ePacket(leds)
        }
    }

    private fun createLbapaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val startFrameSize = 4
        val endFrameSize = max((ledCount + 15) / 16, 4)
        val bytesPerLed = 4
        val dataSize = ledCount * bytesPerLed

        val packet = ByteArray(6 + startFrameSize + dataSize + endFrameSize)

        // Заголовок как у ADA, но с ledCount, а не ledCount-1
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        // LBAPA использует ledCount напрямую, а НЕ ledCount-1, как обычный Adalight
        packet[3] = ((ledCount shr 8) and 0xFF).toByte()
        packet[4] = (ledCount and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Стартовый кадр (4 байта 0x00)
        var offset = 6
        for (i in 0 until startFrameSize) {
            packet[offset++] = 0x00
        }

        // Данные светодиодов: [0xFF, R, G, B] на каждый
        for (led in leds) {
            packet[offset++] = 0xFF.toByte()
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // Завершающий кадр
        for (i in 0 until endFrameSize) {
            packet[offset++] = 0x00
        }

        return packet
    }

    /** TPM2 по последовательному порту: стартовый байт 0xC9, в отличие от 0x9C у tpm2.net. */
    private fun createTpm2Packet(leds: Array<ColorRgb>): ByteArray {
        val dataSize = leds.size * 3
        val packet = ByteArray(4 + dataSize + 1)
        packet[0] = 0xC9.toByte()
        packet[1] = 0xDA.toByte()
        packet[2] = (dataSize shr 8).toByte()
        packet[3] = dataSize.toByte()
        writeRgb(packet, 4, leds, leds.size)
        packet[packet.size - 1] = 0x36
        return packet
    }

    /** Skydimo: заголовок как у Ada, но число светодиодов одним байтом и без контрольной суммы. */
    private fun createSkydimoPacket(leds: Array<ColorRgb>): ByteArray {
        val count = minOf(leds.size, 255)
        val packet = ByteArray(6 + count * 3)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()
        packet[5] = count.toByte()
        writeRgb(packet, 6, leds, count)
        return packet
    }

    /**
     * SEDU: кадр фиксированной длины, режим выбирается по объёму данных; хвост добивается
     * нулями. Больше 1024 светодиодов платы не принимают.
     */
    private fun createSeduPacket(leds: Array<ColorRgb>): ByteArray {
        val count = minOf(leds.size, 1024)
        val (mode, size) = SEDU_FRAMES.first { it.second >= count * 3 }
        val packet = ByteArray(size + 3)
        packet[0] = 0x5A
        packet[1] = mode.toByte()
        writeRgb(packet, 2, leds, count)
        packet[packet.size - 1] = 0xA5.toByte()
        return packet
    }

    /**
     * KarateLight ждёт ровно 8 или 16 каналов в порядке G, B, R; лишние светодиоды
     * отбрасываются, недостающие гаснут. Контрольная сумма: XOR всех байт, кроме неё самой.
     */
    private fun createKaratePacket(leds: Array<ColorRgb>): ByteArray {
        val count = if (leds.size <= 8) 8 else 16
        val packet = ByteArray(4 + count * 3)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x12
        packet[3] = (count * 3).toByte()
        for (i in 0 until minOf(count, leds.size)) {
            packet[4 + i * 3] = leds[i].green.toByte()
            packet[5 + i * 3] = leds[i].blue.toByte()
            packet[6 + i * 3] = leds[i].red.toByte()
        }
        var checksum = packet[0].toInt() xor packet[1].toInt()
        for (i in 3 until packet.size) checksum = checksum xor packet[i].toInt()
        packet[2] = checksum.toByte()
        return packet
    }

    /** AtmoLight: ровно пять каналов, 15 байт данных; лишние светодиоды не уходят. */
    private fun createAtmoPacket(leds: Array<ColorRgb>): ByteArray {
        val packet = ByteArray(4 + ATMO_CHANNELS * 3)
        packet[0] = 0xFF.toByte()
        packet[3] = (ATMO_CHANNELS * 3).toByte()
        writeRgb(packet, 4, leds, minOf(leds.size, ATMO_CHANNELS))
        return packet
    }

    /** SP616E: голый RGB и 0xFF в конце кадра, без заголовка и длины. */
    private fun createSp616ePacket(leds: Array<ColorRgb>): ByteArray {
        val packet = ByteArray(leds.size * 3 + 1)
        writeRgb(packet, 0, leds, leds.size)
        packet[packet.size - 1] = 0xFF.toByte()
        return packet
    }

    private fun writeRgb(packet: ByteArray, from: Int, leds: Array<ColorRgb>, count: Int) {
        var offset = from
        for (i in 0 until count) {
            packet[offset++] = leds[i].red.toByte()
            packet[offset++] = leds[i].green.toByte()
            packet[offset++] = leds[i].blue.toByte()
        }
    }

    private data class PresetValues(
        val settlingTime: Int,
        val outputDelayMs: Long,
        val updateFrequency: Int,
    )

    private fun getPresetValues(preset: String): PresetValues {
        return when (preset.lowercase()) {
            "off" -> PresetValues(50, 0L, 60)
            "responsive" -> PresetValues(50, 0L, 60)
            "balanced" -> PresetValues(200, 80L, 25)
            "smooth" -> PresetValues(500, 200L, 20)
            else -> PresetValues(200, 80L, 25) // balanced по умолчанию
        }
    }

    companion object {
        private const val TAG = "AdalightClient"

        // Стартовый маркер Adalight, который прошивка печатает при загрузке ("Ada\n").
        private const val ADA_MAGIC_WORD = "Ada"

        // Бюджет ожидания хендшейка. Покрывает окно перезагрузки MCU (~1.5–2 c) после подъёма DTR.
        private const val HANDSHAKE_TIMEOUT_MS = 2500L
        private const val AWA_HANDSHAKE_TIMEOUT_MS = 1000L
        private const val HANDSHAKE_READ_TIMEOUT_MS = 250

        private const val KEEPALIVE_MS = 1000L

        private const val ATMO_CHANNELS = 5

        /** Режимы кадра SEDU: код режима и длина данных в байтах. */
        private val SEDU_FRAMES = listOf(0xA1 to 256, 0xA2 to 512, 0xB0 to 768, 0xB1 to 1536, 0xB2 to 3072)
    }
}
