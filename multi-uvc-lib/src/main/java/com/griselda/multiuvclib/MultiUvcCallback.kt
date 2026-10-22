package com.griselda.multiuvclib

import android.view.Surface

interface MultiUvcCallback {
    fun onMainStreamSurfaceRequested(): Surface
    fun onMultiUvcModeChanged(enabled: Boolean)
    fun onBandwidthInsufficient(error: Throwable? = null)
    fun onFpsChanged(fps: Int)
    fun onPanelClosed()
    fun onAllUvcDisconnected()
    fun onUsbEvent(event: String)
}
