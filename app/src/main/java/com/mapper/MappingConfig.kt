package com.mapper

import android.graphics.PointF

// Loại input nguồn
enum class InputType {
    KEY,        // phím bàn phím (KeyEvent.KEYCODE_*)
    MOUSE_LEFT,
    MOUSE_RIGHT,
    MOUSE_MOVE  // di chuyển chuột -> kéo màn hình
}

// Loại action đầu ra trên màn hình
enum class ActionType {
    TAP,            // tap 1 điểm cố định
    SWIPE,          // vuốt từ A đến B
    HOLD,           // giữ ngón tay
    JOYSTICK,       // joystick ảo (drag quanh tâm)
    DRAG_FOLLOW     // drag theo delta chuột (cho nhìn xung quanh)
}

data class Mapping(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",                  // tên hiển thị, vd "Bắn", "Nhảy"
    val inputType: InputType = InputType.KEY,
    val keyCode: Int = 0,                   // KeyEvent.KEYCODE_* nếu inputType = KEY
    val actionType: ActionType = ActionType.TAP,
    val targetX: Float = 0f,                // tọa độ % màn hình (0.0 - 1.0)
    val targetY: Float = 0f,
    val endX: Float = 0f,                   // dùng cho SWIPE
    val endY: Float = 0f,
    val joystickRadius: Float = 0.1f,       // % màn hình, dùng cho JOYSTICK
    val sensitivity: Float = 1.0f,          // nhân tốc độ drag, dùng cho DRAG_FOLLOW
    val enabled: Boolean = true
)

data class MappingProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val profileName: String = "Default",
    val packageName: String = "",           // package game, vd "com.dts.freefireth"
    val mappings: MutableList<Mapping> = mutableListOf()
)
