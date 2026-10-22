package com.griselda.multiuvclib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.opengl.EGLContext
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean

class MultiUvcManager(
    private val context: Context,
    private val callback: MultiUvcCallback,
    private val sharedEglContext: EGLContext?
) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val workerThread = HandlerThread("MultiUvc-Worker")
    private lateinit var workerHandler: Handler
    private val isStarted = AtomicBoolean(false)
    private val lock = Any()

    private var targetConfig = TargetConfig(1920, 1080, 30)
    private var multiMode = false

    private var mainSession: CameraSession? = null
    private var standbySession: CameraSession? = null
    private var panel: MultiUvcPreviewPanel? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let { onDeviceAttached(it) }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let { onDeviceDetached(it) }
                }
            }
        }
    }

    fun setTargetConfig(width: Int, height: Int, fps: Int) {
        targetConfig = TargetConfig(width, height, fps)
        if (!::workerHandler.isInitialized) return
        workerHandler.post {
            synchronized(lock) {
                mainSession?.config = selectBestConfig(targetConfig, HIGH_RES_FORMATS)
                standbySession?.config = selectBestConfig(TargetConfig(640, 480, fps), STANDBY_FORMATS)
            }
        }
    }

    fun onStart() {
        if (!isStarted.compareAndSet(false, true)) return
        workerThread.start()
        workerHandler = Handler(workerThread.looper)
        context.registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        )
        workerHandler.post { scanExistingDevices() }
    }

    fun onStop() {
        if (!isStarted.compareAndSet(true, false)) return
        kotlin.runCatching { context.unregisterReceiver(usbReceiver) }
        if (!::workerHandler.isInitialized) return
        workerHandler.post {
            synchronized(lock) {
                stopSession(mainSession)
                stopSession(standbySession)
            }
        }
    }

    fun onDestroy() {
        onStop()
        if (workerThread.isAlive) {
            workerThread.quitSafely()
        }
        panel = null
    }

    fun createPreviewPanel(parent: ViewGroup): MultiUvcPreviewPanel {
        return (panel ?: MultiUvcPreviewPanel(parent.context).also { previewPanel ->
            previewPanel.setEglContext(sharedEglContext)
            previewPanel.setOnStandbyClickedListener { switchMainAndStandby() }
            previewPanel.setOnCloseConfirmedListener {
                workerHandler.post {
                    synchronized(lock) {
                        stopSession(standbySession)
                        standbySession = null
                        multiMode = false
                    }
                    callback.onPanelClosed()
                    callback.onMultiUvcModeChanged(false)
                }
            }
            val params = FrameLayout.LayoutParams(
                parent.resources.displayMetrics.widthPixels / 2,
                parent.resources.displayMetrics.widthPixels / 3,
                Gravity.TOP or Gravity.END
            )
            parent.addView(previewPanel, params)
            panel = previewPanel
        })
    }

    fun setZoom(level: Float): Boolean {
        val session = synchronized(lock) { mainSession } ?: return false
        return if (session.supportsHardwareZoom) {
            session.zoomLevel = level
            true
        } else {
            false
        }
    }

    fun setPreviewMirror(mainMirror: Boolean, standbyMirror: Boolean) {
        panel?.setMirror(mainMirror, standbyMirror)
    }

    private fun onDeviceAttached(device: UsbDevice) {
        if (!device.isUvcVideoDevice()) return
        workerHandler.post {
            synchronized(lock) {
                if (mainSession != null && standbySession != null) {
                    callback.onUsbEvent("Ignored third UVC device: ${device.deviceName}")
                    return@post
                }
                attachAsMainOrStandby(device)
            }
        }
    }

    private fun onDeviceDetached(device: UsbDevice) {
        if (!device.isUvcVideoDevice()) return
        workerHandler.post {
            synchronized(lock) {
                val wasMain = mainSession?.device?.deviceId == device.deviceId
                val wasStandby = standbySession?.device?.deviceId == device.deviceId

                if (wasMain) {
                    stopSession(mainSession)
                    mainSession = standbySession
                    standbySession = null
                    mainSession?.config = selectBestConfig(targetConfig, HIGH_RES_FORMATS)
                    callback.onFpsChanged(mainSession?.config?.fps ?: targetConfig.fps)
                } else if (wasStandby) {
                    stopSession(standbySession)
                    standbySession = null
                }

                rebuildFromConnectedDevices()
            }
        }
    }

    private fun scanExistingDevices() {
        val uvcDevices = usbManager.deviceList.values.filter { it.isUvcVideoDevice() }
        synchronized(lock) {
            stopSession(mainSession)
            stopSession(standbySession)
            mainSession = null
            standbySession = null
            multiMode = false
            uvcDevices.forEach { attachAsMainOrStandby(it) }
        }
    }

    private fun rebuildFromConnectedDevices() {
        val available = usbManager.deviceList.values.filter { it.isUvcVideoDevice() }
        if (available.isEmpty()) {
            multiMode = false
            callback.onAllUvcDisconnected()
            callback.onMultiUvcModeChanged(false)
            return
        }

        if (mainSession == null) {
            attachAsMainOrStandby(available.first())
        }

        if (standbySession == null && available.size > 1) {
            val fallback = available.firstOrNull { it.deviceId != mainSession?.device?.deviceId }
            fallback?.let { attachAsMainOrStandby(it) }
        }

        if (standbySession == null) {
            multiMode = false
            callback.onMultiUvcModeChanged(false)
        }
    }

    private fun attachAsMainOrStandby(device: UsbDevice) {
        if (mainSession == null) {
            mainSession = openSession(device, isMain = true, targetConfig = targetConfig)
            callback.onFpsChanged(mainSession?.config?.fps ?: this.targetConfig.fps)
            callback.onUsbEvent("Main UVC connected: ${device.deviceName}")
            return
        }

        if (standbySession == null) {
            try {
                standbySession = openSession(device, isMain = false, targetConfig = TargetConfig(640, 480, targetConfig.fps))
                multiMode = true
                callback.onMultiUvcModeChanged(true)
                callback.onUsbEvent("Standby UVC connected: ${device.deviceName}")
            } catch (t: Throwable) {
                multiMode = false
                stopSession(standbySession)
                standbySession = null
                callback.onBandwidthInsufficient(t)
                callback.onMultiUvcModeChanged(false)
            }
        }
    }

    private fun openSession(device: UsbDevice, isMain: Boolean, targetConfig: TargetConfig): CameraSession {
        val configPool = if (isMain) HIGH_RES_FORMATS else STANDBY_FORMATS
        val selectedConfig = selectBestConfig(targetConfig, configPool)
        val session = CameraSession(
            device = device,
            isMain = isMain,
            config = selectedConfig,
            supportsHardwareZoom = isMain
        )

        val usbConnection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Unable to open USB device: ${device.deviceName}")
        session.connection = usbConnection

        if (isMain) {
            session.outputSurface = callback.onMainStreamSurfaceRequested()
        }

        panel?.bindSessions(mainSession, standbySession)
        return session
    }

    private fun stopSession(session: CameraSession?) {
        session?.connection?.close()
        session?.clearFrameCache()
    }

    private fun switchMainAndStandby() {
        workerHandler.post {
            synchronized(lock) {
                val currentMain = mainSession ?: return@synchronized
                val currentStandby = standbySession ?: return@synchronized

                // pause capture and clear stale frame cache before switching
                currentMain.clearFrameCache()
                currentStandby.clearFrameCache()

                stopSession(currentMain)
                stopSession(currentStandby)

                val newMain = openSession(currentStandby.device, isMain = true, targetConfig = targetConfig)
                val newStandby = openSession(
                    currentMain.device,
                    isMain = false,
                    targetConfig = TargetConfig(640, 480, targetConfig.fps)
                )
                mainSession = newMain
                standbySession = newStandby
                panel?.bindSessions(mainSession, standbySession)
                callback.onFpsChanged(newMain.config.fps)
            }
        }
    }

    private fun selectBestConfig(target: TargetConfig, candidates: List<CameraConfig>): CameraConfig {
        val mjpegCandidates = candidates.filter { it.format == FrameFormat.MJPEG }
        val source = if (mjpegCandidates.isNotEmpty()) mjpegCandidates else candidates
        return source.minByOrNull { config ->
            val dw = kotlin.math.abs(config.width - target.width)
            val dh = kotlin.math.abs(config.height - target.height)
            val df = kotlin.math.abs(config.fps - target.fps)
            dw + dh + (df * 10)
        } ?: source.first()
    }

    private fun UsbDevice.isUvcVideoDevice(): Boolean {
        repeat(interfaceCount) { index ->
            val iface = getInterface(index)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    companion object {
        private val HIGH_RES_FORMATS = listOf(
            CameraConfig(1920, 1080, 30, FrameFormat.MJPEG),
            CameraConfig(1280, 720, 30, FrameFormat.MJPEG),
            CameraConfig(1280, 720, 30, FrameFormat.YUV),
            CameraConfig(640, 480, 30, FrameFormat.YUV)
        )

        private val STANDBY_FORMATS = listOf(
            CameraConfig(640, 480, 30, FrameFormat.MJPEG),
            CameraConfig(640, 480, 30, FrameFormat.YUV),
            CameraConfig(320, 240, 30, FrameFormat.YUV)
        )
    }
}

data class TargetConfig(
    val width: Int,
    val height: Int,
    val fps: Int
)

data class CameraConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: FrameFormat
)

enum class FrameFormat {
    MJPEG,
    YUV
}

data class CameraSession(
    val device: UsbDevice,
    val isMain: Boolean,
    var config: CameraConfig,
    val supportsHardwareZoom: Boolean,
    var outputSurface: android.view.Surface? = null,
    var zoomLevel: Float = 1f,
    var connection: android.hardware.usb.UsbDeviceConnection? = null
) {
    fun clearFrameCache() {
        // Placeholder for frame queue cleanup in production pipeline.
    }
}
