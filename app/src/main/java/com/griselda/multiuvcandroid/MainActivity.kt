package com.griselda.multiuvcandroid

import android.opengl.EGL14
import android.opengl.EGLContext
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.griselda.multiuvclib.MultiUvcCallback
import com.griselda.multiuvclib.MultiUvcManager
import com.griselda.multiuvclib.MultiUvcPreviewPanel

class MainActivity : AppCompatActivity(), MultiUvcCallback {

    private lateinit var rootContainer: FrameLayout
    private lateinit var mainSurfaceView: SurfaceView
    private lateinit var manager: MultiUvcManager
    private var panel: MultiUvcPreviewPanel? = null
    private var outputSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        mainSurfaceView = findViewById(R.id.mainSurfaceView)
        mainSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                outputSurface = holder.surface
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                outputSurface = null
            }
        })

        val eglContext: EGLContext = EGL14.eglGetCurrentContext()
        manager = MultiUvcManager(this, this, eglContext)
        manager.setTargetConfig(1920, 1080, 30)
    }

    override fun onStart() {
        super.onStart()
        manager.onStart()
    }

    override fun onStop() {
        manager.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        manager.onDestroy()
        super.onDestroy()
    }

    override fun onMainStreamSurfaceRequested(): Surface = outputSurface ?: mainSurfaceView.holder.surface

    override fun onMultiUvcModeChanged(enabled: Boolean) {
        runOnUiThread {
            if (enabled) {
                if (panel == null) {
                    panel = manager.createPreviewPanel(rootContainer)
                }
            } else {
                panel?.let { rootContainer.removeView(it) }
                panel = null
            }
        }
    }

    override fun onBandwidthInsufficient(error: Throwable?) {
        Log.w(TAG, "USB bandwidth is insufficient", error)
    }

    override fun onFpsChanged(fps: Int) {
        Log.d(TAG, "FPS changed: $fps")
    }

    override fun onPanelClosed() {
        runOnUiThread {
            panel?.let { rootContainer.removeView(it) }
            panel = null
        }
    }

    override fun onAllUvcDisconnected() {
        Log.i(TAG, "All UVC devices disconnected")
        runOnUiThread {
            panel?.let { rootContainer.removeView(it) }
            panel = null
        }
    }

    override fun onUsbEvent(event: String) {
        Log.d(TAG, "USB event: $event")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
