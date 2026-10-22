package com.griselda.multiuvclib

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.opengl.EGLContext
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import com.google.android.material.color.MaterialColors
import kotlin.math.max

class MultiUvcPreviewPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val titleView = TextView(context)
    private val closeButton = ImageButton(context)
    private val mainPreview = TextureView(context)
    private val standbyPreview = TextureView(context)
    private val previewContainer = LinearLayout(context)
    private val resizeHandle = View(context)

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val params = layoutParams ?: return false
            val width = max((params.width * detector.scaleFactor).toInt(), minimumPanelSize)
            val height = max((params.height * detector.scaleFactor).toInt(), minimumPanelSize)
            updateSize(width, height)
            return true
        }
    })

    private var lastRawX = 0f
    private var lastRawY = 0f

    private var standbyClickListener: (() -> Unit)? = null
    private var closeConfirmedListener: (() -> Unit)? = null

    private var eglContext: EGLContext? = null

    init {
        setBackgroundResource(R.drawable.bg_multi_uvc_panel)
        clipToOutline = true
        elevation = 8f

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleView.apply {
            text = context.getString(R.string.multi_uvc_title, 1)
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        closeButton.apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setOnClickListener { showCloseDialog() }
        }

        titleContainer.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleContainer.addView(closeButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        previewContainer.orientation = LinearLayout.HORIZONTAL
        previewContainer.gravity = Gravity.CENTER

        val previewParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            marginEnd = dp(4)
        }
        mainPreview.layoutParams = previewParams
        standbyPreview.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        standbyPreview.setOnClickListener { standbyClickListener?.invoke() }

        previewContainer.addView(mainPreview)
        previewContainer.addView(standbyPreview)

        resizeHandle.apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0.7f
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val width = max((event.rawX - x).toInt(), minimumPanelSize)
                    val height = max((event.rawY - y).toInt(), minimumPanelSize)
                    updateSize(width, height)
                }
                true
            }
        }

        root.addView(titleContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        root.addView(previewContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(resizeHandle, LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END))
    }

    fun setEglContext(eglContext: EGLContext?) {
        this.eglContext = eglContext
    }

    fun bindSessions(mainSession: CameraSession?, standbySession: CameraSession?) {
        titleView.text = context.getString(R.string.multi_uvc_title, if (mainSession != null) 1 else 0)
        mainPreview.rotation = 0f
        standbyPreview.rotation = 0f
        mainPreview.scaleX = 1f
        standbyPreview.scaleX = 1f
        if (standbySession == null) {
            standbyPreview.alpha = 0.4f
        } else {
            standbyPreview.alpha = 1f
        }
    }

    fun setMirror(mainMirror: Boolean, standbyMirror: Boolean) {
        mainPreview.scaleX = if (mainMirror) -1f else 1f
        standbyPreview.scaleX = if (standbyMirror) -1f else 1f
    }

    fun setOnStandbyClickedListener(listener: () -> Unit) {
        standbyClickListener = listener
    }

    fun setOnCloseConfirmedListener(listener: () -> Unit) {
        closeConfirmedListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastRawX
                val deltaY = event.rawY - lastRawY
                translationX += deltaX
                translationY += deltaY
                clampInParent()
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun showCloseDialog() {
        AlertDialog.Builder(context)
            .setTitle(R.string.multi_uvc_close_confirm_title)
            .setMessage(R.string.multi_uvc_close_confirm_message)
            .setPositiveButton(R.string.multi_uvc_yes) { _, _ -> closeConfirmedListener?.invoke() }
            .setNegativeButton(R.string.multi_uvc_no, null)
            .show()
    }

    private fun updateSize(width: Int, height: Int) {
        updateLayoutParams<FrameLayout.LayoutParams> {
            this.width = width
            this.height = height
        }
        previewContainer.orientation = if (width > height) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }

    private fun clampInParent() {
        val parentView = parent as? View ?: return
        val maxX = (parentView.width - width).toFloat()
        val maxY = (parentView.height - height).toFloat()
        translationX = translationX.coerceIn(0f, maxX)
        translationY = translationY.coerceIn(0f, maxY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val minimumPanelSize = 220
    }
}
