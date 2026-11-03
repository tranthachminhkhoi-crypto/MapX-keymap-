package com.mapper

import android.graphics.PointF
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Inject touch event thông qua Shizuku -> IInputManager.injectInputEvent()
 * Cần: Shizuku đang chạy + app được grant permission Shizuku
 */
object InputInjector {

    // Lấy IInputManager qua Shizuku binder
    private val inputManager: InputManager by lazy {
        val binder = SystemServiceHelper.getSystemService("input")
        val wrapper = ShizukuBinderWrapper(binder)
        // Dùng reflection để tạo InputManager từ binder
        val clazz = Class.forName("android.hardware.input.InputManager")
        val method = clazz.getDeclaredMethod("getInstance")
        method.invoke(null) as InputManager
    }

    // Inject qua IInputManager hidden API (cần Shizuku permission)
    private fun injectEvent(event: MotionEvent) {
        try {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val method = clazz.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            // MODE_ASYNC = 0, không block
            method.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Public API ---

    /**
     * Tap 1 điểm (DOWN + UP liên tiếp)
     * x, y: pixel tuyệt đối
     */
    fun tap(x: Float, y: Float, durationMs: Long = 50) {
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_DOWN, x, y, 0
        ).also { it.source = InputDevice.SOURCE_TOUCHSCREEN }

        val up = MotionEvent.obtain(
            downTime, downTime + durationMs,
            MotionEvent.ACTION_UP, x, y, 0
        ).also { it.source = InputDevice.SOURCE_TOUCHSCREEN }

        injectEvent(down)
        Thread.sleep(durationMs)
        injectEvent(up)

        down.recycle()
        up.recycle()
    }

    /**
     * Giữ ngón tay (DOWN, giữ cho đến khi gọi release)
     * Trả về downTime để dùng trong release/move
     */
    fun pressDown(x: Float, y: Float, pointerId: Int = 0): Long {
        val downTime = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = pointerId
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x; it.y = y; it.pressure = 1f; it.size = 1f
        })
        val event = MotionEvent.obtain(
            downTime, downTime,
            MotionEvent.ACTION_DOWN, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(event)
        event.recycle()
        return downTime
    }

    /**
     * Di chuyển ngón tay (MOVE) — dùng cho joystick / drag
     */
    fun movePointer(downTime: Long, x: Float, y: Float, pointerId: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = pointerId
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x; it.y = y; it.pressure = 1f; it.size = 1f
        })
        val event = MotionEvent.obtain(
            downTime, now,
            MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(event)
        event.recycle()
    }

    /**
     * Nhả ngón tay (UP)
     */
    fun release(downTime: Long, x: Float, y: Float, pointerId: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().also {
            it.id = pointerId
            it.toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().also {
            it.x = x; it.y = y; it.pressure = 0f; it.size = 0f
        })
        val event = MotionEvent.obtain(
            downTime, now,
            MotionEvent.ACTION_UP, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(event)
        event.recycle()
    }

    /**
     * Swipe từ (x1,y1) đến (x2,y2) trong durationMs
     * steps: số bước move giữa chừng (càng nhiều càng mượt)
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float,
              durationMs: Long = 200, steps: Int = 10) {
        val downTime = pressDown(x1, y1)
        val stepDelay = durationMs / steps
        for (i in 1..steps) {
            val frac = i.toFloat() / steps
            movePointer(downTime, x1 + (x2 - x1) * frac, y1 + (y2 - y1) * frac)
            Thread.sleep(stepDelay)
        }
        release(downTime, x2, y2)
    }
}
