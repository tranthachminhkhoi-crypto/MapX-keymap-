package com.mapper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * MainActivity
 * - Kiểm tra permission: SYSTEM_ALERT_WINDOW + Shizuku
 * - Hiển thị danh sách mapping hiện tại
 * - Nút thêm / sửa / xóa mapping
 * - Nút Start / Stop overlay service
 */
class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQUEST = 1001
    private val SHIZUKU_PERMISSION_REQUEST = 1002

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var mappingListLayout: LinearLayout

    // Profile đang edit (đơn giản, production nên dùng ViewModel + Room)
    private val currentProfile = MappingProfile(
        profileName = "Free Fire",
        packageName = "com.dts.freefireth"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout tạo bằng code cho gọn (không cần XML)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        statusText = TextView(this).apply {
            text = "Checking permissions..."
            textSize = 14f
        }

        startButton = Button(this).apply {
            text = "Start Mapper"
            isEnabled = false
            setOnClickListener { toggleService() }
        }

        val addMappingBtn = Button(this).apply {
            text = "+ Add Mapping"
            setOnClickListener { showAddMappingDialog() }
        }

        mappingListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this)
        scroll.addView(mappingListLayout)

        root.addView(statusText)
        root.addView(startButton)
        root.addView(addMappingBtn)
        root.addView(scroll)

        setContentView(root)

        // Load demo mappings
        loadDemoMappings()
        checkPermissions()
    }

    // --- Permission checks ---

    private fun checkPermissions() {
        val overlayOk = Settings.canDrawOverlays(this)
        val shizukuOk = isShizukuReady()

        val status = buildString {
            appendLine("Overlay permission: ${if (overlayOk) "✓" else "✗ (cần cấp)"}")
            appendLine("Shizuku: ${if (shizukuOk) "✓ Ready" else "✗ (chưa chạy hoặc chưa grant)"}")
        }
        statusText.text = status
        startButton.isEnabled = overlayOk && shizukuOk

        if (!overlayOk) requestOverlayPermission()
        if (!shizukuOk) requestShizukuPermission()
    }

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) { false }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        } else {
            Toast.makeText(this, "Shizuku chưa chạy. Mở app Shizuku trước.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) checkPermissions()
    }

    // --- Service toggle ---

    private var serviceRunning = false

    private fun toggleService() {
        if (serviceRunning) {
            stopService(Intent(this, OverlayService::class.java))
            startButton.text = "Start Mapper"
            serviceRunning = false
        } else {
            startService(Intent(this, OverlayService::class.java))
            startButton.text = "Stop Mapper"
            serviceRunning = true
        }
    }

    // --- Mapping list UI ---

    private fun loadDemoMappings() {
        // Demo: Free Fire cơ bản
        currentProfile.mappings.addAll(listOf(
            Mapping(
                name = "Bắn (chuột trái)",
                inputType = InputType.MOUSE_LEFT,
                actionType = ActionType.TAP,
                targetX = 0.93f, targetY = 0.75f  // vị trí nút bắn % màn hình
            ),
            Mapping(
                name = "Nhảy (Space)",
                inputType = InputType.KEY,
                keyCode = android.view.KeyEvent.KEYCODE_SPACE,
                actionType = ActionType.TAP,
                targetX = 0.88f, targetY = 0.82f
            ),
            Mapping(
                name = "Nằm (Z)",
                inputType = InputType.KEY,
                keyCode = android.view.KeyEvent.KEYCODE_Z,
                actionType = ActionType.HOLD,
                targetX = 0.82f, targetY = 0.82f
            ),
            Mapping(
                name = "Nhìn xung quanh (chuột move)",
                inputType = InputType.MOUSE_MOVE,
                actionType = ActionType.DRAG_FOLLOW,
                targetX = 0.5f, targetY = 0.5f,
                sensitivity = 1.5f
            )
        ))
        refreshMappingList()
    }

    private fun refreshMappingList() {
        mappingListLayout.removeAllViews()
        currentProfile.mappings.forEachIndexed { index, mapping ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val label = TextView(this).apply {
                text = "${mapping.name}  [${mapping.actionType}]  " +
                       "(${(mapping.targetX * 100).toInt()}%, ${(mapping.targetY * 100).toInt()}%)"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 12f
            }

            val deleteBtn = Button(this).apply {
                text = "✕"
                textSize = 10f
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    currentProfile.mappings.removeAt(index)
                    refreshMappingList()
                }
            }

            row.addView(label)
            row.addView(deleteBtn)
            mappingListLayout.addView(row)
        }
    }

    private fun showAddMappingDialog() {
        // Dialog đơn giản — production nên dùng DialogFragment với visual picker
        val dialog = android.app.AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val nameInput = EditText(this).apply { hint = "Tên (vd: Bắn)" }
        val keyInput = EditText(this).apply { hint = "KeyCode (vd: 62 = Space)" }
        val xInput = EditText(this).apply { hint = "Target X (0.0 - 1.0)" }
        val yInput = EditText(this).apply { hint = "Target Y (0.0 - 1.0)" }

        layout.addView(nameInput)
        layout.addView(keyInput)
        layout.addView(xInput)
        layout.addView(yInput)

        dialog.setView(layout)
            .setTitle("Add Mapping")
            .setPositiveButton("Add") { _, _ ->
                val mapping = Mapping(
                    name = nameInput.text.toString(),
                    inputType = InputType.KEY,
                    keyCode = keyInput.text.toString().toIntOrNull() ?: 0,
                    actionType = ActionType.TAP,
                    targetX = xInput.text.toString().toFloatOrNull() ?: 0.5f,
                    targetY = yInput.text.toString().toFloatOrNull() ?: 0.5f
                )
                currentProfile.mappings.add(mapping)
                refreshMappingList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
