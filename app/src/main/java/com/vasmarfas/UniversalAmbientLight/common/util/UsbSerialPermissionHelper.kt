package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.UsbSerialDeviceSelector

/**
 * Запрашивает разрешение на первое (или конкретное) USB-последовательное устройство.
 *
 * Задачи: сделать запуск «в одно касание» из MainActivity, BootActivity, ToggleActivity и
 * плитки быстрых настроек, а также при желании запрашивать разрешение автоматически, когда
 * устройство подключают при открытом приложении.
 */
object UsbSerialPermissionHelper {
    private const val TAG = "UsbSerialPermission"

    // Значение action менять нельзя — оно уже используется в MainActivity
    const val ACTION_USB_PERMISSION = "com.vasmarfas.UniversalAmbientLight.USB_PERMISSION"

    @Volatile
    private var lastRequestedDeviceId: Int? = null

    fun hasAnySerialDevice(context: Context): Boolean {
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProberFactory.getProber().findAllDrivers(usbManager).isNotEmpty()
    }

    fun findFirstSerialDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val drivers = UsbSerialProberFactory.getProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) return null
        return UsbSerialDeviceSelector.select(drivers) { usbManager.hasPermission(it.device) }.device
    }

    fun isSerialDevice(context: Context, device: UsbDevice): Boolean {
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProberFactory.getProber()
            .findAllDrivers(usbManager)
            .any { it.device.deviceId == device.deviceId }
    }

    /**
     * Обеспечивает разрешение на USB-последовательное устройство Adalight.
     *
     * Если разрешение уже выдано, [onReady] вызывается сразу; иначе разрешение запрашивается,
     * и [onReady] вызывается после согласия пользователя. Колбэки приходят на главном потоке.
     *
     * @return true, если всё уже готово, и false, если запрос только начат.
     */
    fun ensurePermissionForSerialDevice(
        context: Context,
        device: UsbDevice?,
        onReady: () -> Unit,
        onDenied: (() -> Unit)? = null,
        showToast: Boolean = true,
        force: Boolean = false,
    ): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            if (showToast) Toast.makeText(
                context,
                R.string.usb_service_unavailable,
                Toast.LENGTH_LONG
            ).show()
            onDenied?.invoke()
            return false
        }

        val target = device ?: findFirstSerialDevice(context)
        if (target == null) {
            if (showToast) Toast.makeText(
                context,
                R.string.usb_no_serial_devices,
                Toast.LENGTH_LONG
            ).show()
            onDenied?.invoke()
            return false
        }

        // Не спрашиваем про устройства, не являющиеся последовательными (случайная USB-периферия)
        if (!isSerialDevice(context, target)) {
            onDenied?.invoke()
            return false
        }

        if (usbManager.hasPermission(target)) {
            onReady()
            return true
        }

        // Проверка root запускает `su` и блокирует до 2 секунд, а сюда приходят с главного
        // потока (onReceive, onCreate активити) — при непрогретом кэше уводим её в фон.
        val cachedRoot = UsbRootPermissionHelper.isRootAvailableCached()
        if (cachedRoot == null) {
            Thread({
                val rootAvailable = UsbRootPermissionHelper.isRootAvailable()
                Handler(Looper.getMainLooper()).post {
                    proceedWithPermission(
                        context, usbManager, target, rootAvailable,
                        onReady, onDenied, showToast, force
                    )
                }
            }, "UsbRootCheck").apply { isDaemon = true }.start()
            return false
        }

        proceedWithPermission(
            context, usbManager, target, cachedRoot, onReady, onDenied, showToast, force
        )
        return false
    }

    private fun proceedWithPermission(
        context: Context,
        usbManager: UsbManager,
        target: UsbDevice,
        rootAvailable: Boolean,
        onReady: () -> Unit,
        onDenied: (() -> Unit)?,
        showToast: Boolean,
        force: Boolean,
    ) {
        // Сначала пробуем root, только потом системный диалог; `su` блокирует, поэтому асинхронно.
        if (rootAvailable) {
            if (!force && lastRequestedDeviceId == target.deviceId) {
                // Запрос уже был и не удался: молчаливый выход без колбэка подвесил бы
                // вызывающих, которые ждут любого из двух исходов (BootActivity ждёт
                // finish() именно здесь)
                onDenied?.invoke()
                return
            }
            lastRequestedDeviceId = target.deviceId

            UsbRootPermissionHelper.grantPermissionViaRootAsync(context) { rootGranted ->
                Handler(Looper.getMainLooper()).post {
                    if (rootGranted && usbManager.hasPermission(target)) {
                        onReady()
                    } else {
                        // Root не помог (SELinux, урезанный su) — падаем на системный
                        // диалог, иначе пользователь с root вообще не увидел бы запроса
                        lastRequestedDeviceId = null
                        requestViaSystemDialog(
                            context, usbManager, target, onReady, onDenied, showToast, force
                        )
                    }
                }
            }
            return
        }

        requestViaSystemDialog(context, usbManager, target, onReady, onDenied, showToast, force)
    }

    private fun requestViaSystemDialog(
        context: Context,
        usbManager: UsbManager,
        target: UsbDevice,
        onReady: () -> Unit,
        onDenied: (() -> Unit)?,
        showToast: Boolean,
        force: Boolean,
    ) {
        // Не показываем один и тот же запрос по кругу; вызывающему это «отказ», а не тишина
        if (!force && lastRequestedDeviceId == target.deviceId) {
            onDenied?.invoke()
            return
        }
        lastRequestedDeviceId = target.deviceId

        AnalyticsHelper.logUsbPermissionRequested(context)

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            target.deviceId,
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                    // Одноразовый приёмник: система могла снять его вместе с контекстом.
                }

                val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val targetDevice = deviceFromIntent ?: target
                val grantedByBroadcast =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                // Часть прошивок присылает неверный или пустой EXTRA_PERMISSION_GRANTED — перепроверяем сами.
                val grantedByManager = usbManager.hasPermission(targetDevice)
                val granted = grantedByBroadcast || grantedByManager

                if (granted) {
                    AnalyticsHelper.logUsbPermissionGranted(ctx)
                    onReady()
                } else {
                    AnalyticsHelper.logUsbPermissionDenied(ctx)
                    if (showToast) {
                        Toast.makeText(
                            ctx,
                            R.string.usb_permission_denied_toast,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onDenied?.invoke()
                }
            }
        }

        // Приёмник регистрируем как неэкспортируемый и на контексте приложения: активити
        // могут уничтожить, пока системный диалог USB открыт, и приёмник на её контексте
        // утёк бы вместе с ней (IntentReceiverLeaked)
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            usbManager.requestPermission(target, permissionIntent)
            if (showToast) Toast.makeText(
                context,
                R.string.usb_permission_prompt,
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}", e)
            // Диалог не открылся — широковещания не будет, приёмник снимаем сами,
            // иначе он утёк бы вместе с захваченными колбэками
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            onDenied?.invoke()
        }
    }
}
