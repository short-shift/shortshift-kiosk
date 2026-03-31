package com.shortshift.kiosk

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Admin-utgang som kan legges til ALLE activities.
 * Gesture: 2x oppe-høyre + 2x nede-høyre → PIN-dialog → utgang til Android.
 * Bruk: kall handleTouchEvent(ev) fra dispatchTouchEvent().
 */
class AdminEscape(private val activity: Activity) {

    companion object {
        private const val TAG = "AdminEscape"
        const val DEFAULT_PIN = "3023"
        private const val CORNER_FRACTION = 0.15f // 15% av skjermkanten
        private const val GESTURE_TIMEOUT_MS = 5000L
        private const val TAP_INTERVAL_MS = 1000L
    }

    private var gesturePhase = 0 // 0=venter, 1=har 2x oppe-høyre, 2=ferdig
    private var tapCount = 0
    private var lastTapTime = 0L
    private var gestureStartTime = 0L

    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val cornerW = (screenW * CORNER_FRACTION).toInt()
        val cornerH = (screenH * CORNER_FRACTION).toInt()

        val isTopLeft = event.x < cornerW && event.y < cornerH
        val isBottomRight = event.x > screenW - cornerW && event.y > screenH - cornerH

        // Reset ved timeout
        if (gestureStartTime > 0 && now - gestureStartTime > GESTURE_TIMEOUT_MS) {
            resetGesture()
        }

        when (gesturePhase) {
            0 -> {
                if (isTopLeft) {
                    if (tapCount == 0) gestureStartTime = now
                    if (tapCount == 0 || now - lastTapTime < TAP_INTERVAL_MS) {
                        tapCount++
                        lastTapTime = now
                        if (tapCount >= 2) {
                            gesturePhase = 1
                            tapCount = 0
                            Log.i(TAG, "Fase 1: 2x oppe-venstre OK")
                            // Skjul tastaturet så nede-høyre er tilgjengelig
                            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                            activity.currentFocus?.let { view ->
                                imm.hideSoftInputFromWindow(view.windowToken, 0)
                                view.clearFocus()
                            }
                        }
                    } else {
                        resetGesture()
                    }
                } else {
                    resetGesture()
                }
            }
            1 -> {
                if (isBottomRight) {
                    if (tapCount == 0 || now - lastTapTime < TAP_INTERVAL_MS) {
                        tapCount++
                        lastTapTime = now
                        if (tapCount >= 2) {
                            Log.i(TAG, "Admin-gesture fullført — viser PIN-dialog")
                            resetGesture()
                            showPinDialog()
                            return true
                        }
                    } else {
                        resetGesture()
                    }
                } else if (!isTopLeft) {
                    resetGesture()
                }
            }
        }
        return false
    }

    private fun resetGesture() {
        gesturePhase = 0
        tapCount = 0
        gestureStartTime = 0
    }

    private fun showPinDialog() {
        val root = activity.window.decorView as FrameLayout
        val enteredPin = StringBuilder()

        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(0xDD000000.toInt())
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(48, 32, 48, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(activity).apply {
            text = "Admin PIN"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        val pinDisplay = TextView(activity).apply {
            text = "_ _ _ _"
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF333333.toInt())
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
        }
        card.addView(pinDisplay, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        val errorText = TextView(activity).apply {
            textSize = 14f
            setTextColor(0xFFFF4444.toInt())
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
        }
        card.addView(errorText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        fun updatePinDisplay() {
            pinDisplay.text = if (enteredPin.isEmpty()) "_ _ _ _"
            else enteredPin.map { "●" }.joinToString(" ") +
                " _".repeat((4 - enteredPin.length).coerceAtLeast(0))
        }

        fun verifyPin() {
            if (enteredPin.toString() == DEFAULT_PIN) {
                Log.i(TAG, "PIN korrekt — avslutter til Android")
                root.removeView(overlay)
                exitToAndroid()
            } else {
                errorText.text = "Feil PIN"
                errorText.visibility = android.view.View.VISIBLE
                enteredPin.clear()
                updatePinDisplay()
            }
        }

        // Numpad: 1-9, ⌫, 0, OK
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val dp = activity.resources.displayMetrics.density

        for (row in keys.chunked(3)) {
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (key in row) {
                val btn = Button(activity).apply {
                    text = key
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundColor(when (key) {
                        "OK" -> 0xFF4CAF50.toInt()
                        "⌫" -> 0xFF555555.toInt()
                        else -> 0xFF333333.toInt()
                    })
                    setOnClickListener {
                        when (key) {
                            "⌫" -> {
                                if (enteredPin.isNotEmpty()) {
                                    enteredPin.deleteCharAt(enteredPin.length - 1)
                                    updatePinDisplay()
                                    errorText.visibility = android.view.View.GONE
                                }
                            }
                            "OK" -> verifyPin()
                            else -> {
                                if (enteredPin.length < 4) {
                                    enteredPin.append(key)
                                    updatePinDisplay()
                                    errorText.visibility = android.view.View.GONE
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn, LinearLayout.LayoutParams(
                    0, (56 * dp).toInt(), 1f
                ).apply { setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt()) })
            }
            card.addView(rowLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Avbryt-knapp
        val cancelBtn = Button(activity).apply {
            text = "Avbryt"
            textSize = 16f
            setBackgroundColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { root.removeView(overlay) }
        }
        card.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * dp).toInt()
        ).apply { topMargin = (8 * dp).toInt() })

        val cardParams = FrameLayout.LayoutParams((400 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        overlay.addView(card, cardParams)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun exitToAndroid() {
        try {
            activity.stopLockTask()
            Log.i(TAG, "Lock task stoppet")
        } catch (e: Exception) {
            Log.d(TAG, "stopLockTask: ${e.message} (OK hvis ikke i lock task)")
        }
        activity.finish()
    }
}
