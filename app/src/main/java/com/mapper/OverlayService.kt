package com.mapper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import kotlinx.coroutines.*

/**
 * Service vẽ overlay toàn màn hình (invisible).
 * Nhận KeyEvent từ chuột/bàn phím vật lý và dispatch sang InputInjector.
 *
 * Cần permission: SYSTEM_ALERT_WINDOW
 * Cần Shizuku cho InputInjector
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var screenWidth = 0
    private var screenHeight = 0

    // Active profile được load từ SharedPreferences / truyền qua Intent
    private var activeProfile: MappingProfile = MappingProfile()

    // Trạng thái giữ phím: keyCode -> PointerState
    private data class PointerState(
        val downTime: Long,
        val x: Float,
        val y: Float,
        val pointerId: Int
    )
    private val heldKeys = mutableMapOf<Int, PointerState>()

    // Pointer ID pool (multi-touch, tối đa 10 ngón)
    private val pointerPool = ArrayDeque((0..9).toList())
    private val usedPointers = mutableMapOf<Int, Int>() // keyCode -> pointerId

    // Joystick state
    private data class JoystickState(
        val centerX: Float,
        val centerY: Float,
        val downTime: Long,
        val pointerId: Int
    )
    private val joystickStates = mutableMapOf<String, JoystickState>() // mappingId -> state

    // Mouse drag state (cho DRAG_FOLLOW)
    private var dragDownTime: Long? = null
    private var dragCurrentX = 0f
    private var dragCurrentY = 0f
    private var dragPointerId = -1

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            // Load profile từ intent (serialize đơn giản qua JSON string hoặc Parcelable)
            // TODO: implement serialization, hiện tại dùng profile mặc định
        }
        return START_STICKY
    }

    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE để game vẫn nhận focus, nhưng ta override dispatchKeyEvent
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        overlayView = object : View(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                return handleKeyEvent(event) || super.dispatchKeyEvent(event)
            }

            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                return handleMouseMotion(event) || super.onGenericMotionEvent(event)
            }
        }

        // Cho overlay nhận key event
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        windowManager.addView(overlayView, params)
        overlayView.requestFocus()
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val mapping = activeProfile.mappings.firstOrNull {
            it.enabled && it.inputType == InputType.KEY && it.keyCode == event.keyCode
        } ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleMappingDown(mapping)
            KeyEvent.ACTION_UP   -> handleMappingUp(mapping)
        }
        return true
    }

    private fun handleMouseMotion(event: MotionEvent): Boolean {
        if (event.source != InputDevice.SOURCE_MOUSE) return false

        val dragMappings = activeProfile.mappings.filter {
            it.enabled && it.actionType == ActionType.DRAG_FOLLOW
        }
        if (dragMappings.isEmpty()) return false

        val mapping = dragMappings.first()
        val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) * mapping.sensitivity
        val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) * mapping.sensitivity

        val downTime = dragDownTime
        if (downTime != null) {
            dragCurrentX = (dragCurrentX + dx).coerceIn(0f, screenWidth.toFloat())
            dragCurrentY = (dragCurrentY + dy).coerceIn(0f, screenHeight.toFloat())
            serviceScope.launch {
                InputInjector.movePointer(downTime, dragCurrentX, dragCurrentY, dragPointerId)
            }
        }
        return true
    }

    private fun handleMappingDown(mapping: Mapping) {
        val absX = mapping.targetX * screenWidth
        val absY = mapping.targetY * screenHeight

        serviceScope.launch {
            when (mapping.actionType) {
                ActionType.TAP -> {
                    // TAP: inject ngay, không cần giữ
                    InputInjector.tap(absX, absY)
                }

                ActionType.HOLD -> {
                    if (!heldKeys.containsKey(mapping.keyCode)) {
                        val pid = allocPointer(mapping.keyCode)
                        val downTime = InputInjector.pressDown(absX, absY, pid)
                        heldKeys[mapping.keyCode] = PointerState(downTime, absX, absY, pid)
                    }
                }

                ActionType.SWIPE -> {
                    val endAbsX = mapping.endX * screenWidth
                    val endAbsY = mapping.endY * screenHeight
                    InputInjector.swipe(absX, absY, endAbsX, endAbsY)
                }

                ActionType.JOYSTICK -> {
                    // Joystick: center DOWN khi nhấn, MOVE khi nhấn kết hợp hướng
                    // Hướng được map bởi W/A/S/D riêng (xem note bên dưới)
                    if (!joystickStates.containsKey(mapping.id)) {
                        val pid = allocPointer(mapping.keyCode)
                        val downTime = InputInjector.pressDown(absX, absY, pid)
                        joystickStates[mapping.id] = JoystickState(absX, absY, downTime, pid)
                    }
                }

                ActionType.DRAG_FOLLOW -> {
                    // Bắt đầu drag theo chuột
                    if (dragDownTime == null) {
                        val centerX = mapping.targetX * screenWidth
                        val centerY = mapping.targetY * screenHeight
                        dragCurrentX = centerX
                        dragCurrentY = centerY
                        dragPointerId = allocPointer(mapping.keyCode)
                        dragDownTime = InputInjector.pressDown(centerX, centerY, dragPointerId)
                    }
                }
            }
        }
    }

    private fun handleMappingUp(mapping: Mapping) {
        serviceScope.launch {
            when (mapping.actionType) {
                ActionType.HOLD -> {
                    heldKeys.remove(mapping.keyCode)?.let { state ->
                        InputInjector.release(state.downTime, state.x, state.y, state.pointerId)
                        freePointer(mapping.keyCode)
                    }
                }

                ActionType.JOYSTICK -> {
                    joystickStates.remove(mapping.id)?.let { state ->
                        InputInjector.release(state.downTime, state.centerX, state.centerY, state.pointerId)
                        freePointer(mapping.keyCode)
                    }
                }

                ActionType.DRAG_FOLLOW -> {
                    dragDownTime?.let { dt ->
                        InputInjector.release(dt, dragCurrentX, dragCurrentY, dragPointerId)
                        dragDownTime = null
                        freePointer(mapping.keyCode)
                        dragPointerId = -1
                    }
                }

                else -> { /* TAP và SWIPE không có state giữ */ }
            }
        }
    }

    // --- Pointer ID management ---

    private fun allocPointer(keyCode: Int): Int {
        val pid = pointerPool.removeFirstOrNull() ?: 0
        usedPointers[keyCode] = pid
        return pid
    }

    private fun freePointer(keyCode: Int) {
        usedPointers.remove(keyCode)?.let { pointerPool.addFirst(it) }
    }

    // --- Joystick WASD helper ---
    // Gọi từ ngoài khi W/A/S/D được nhấn, để move joystick về hướng tương ứng

    fun moveJoystick(mappingId: String, dirX: Float, dirY: Float, radiusPx: Float) {
        val state = joystickStates[mappingId] ?: return
        val targetX = state.centerX + dirX * radiusPx
        val targetY = state.centerY + dirY * radiusPx
        serviceScope.launch {
            InputInjector.movePointer(state.downTime, targetX, targetY, state.pointerId)
        }
    }

    fun returnJoystickToCenter(mappingId: String) {
        val state = joystickStates[mappingId] ?: return
        serviceScope.launch {
            InputInjector.movePointer(state.downTime, state.centerX, state.centerY, state.pointerId)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
