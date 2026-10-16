package com.wearos.ancsbridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.permission.PermissionController
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
        }
        requestHealthConnectPermissions()
    }

    private val healthConnectLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.i("MainActivity", "Health Connect permissions granted: ${granted.size}")
        promptBatteryExemptionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            AncsBridgeTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
            requestHealthConnectPermissions()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestHealthConnectPermissions() {
        try {
            if (HealthConnectClient.sdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return
            val client = HealthConnectClient.getOrCreate(this)
            val permissions = setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(ExerciseRoute::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
            )
            if (!client.permissionController.hasAllPermissions(permissions)) {
                healthConnectLauncher.launch(permissions)
            } else {
                promptBatteryExemptionIfNeeded()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Health Connect unavailable: ${e.message}")
            promptBatteryExemptionIfNeeded()
        }
    }

    /**
     * OnePlus Watch 3 battery management kills background BLE unless the app is
     * exempt from battery optimization (the <12h-battery failure mode seen in
     * commercial bridge apps). Prompted once per install.
     */
    private fun promptBatteryExemptionIfNeeded() {
        val prefs = getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_shown", false)) return

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_prompt_shown", true).apply()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            // Mark as shown only after the prompt actually launched — a failed
            // launch (ActivityNotFoundException etc.) retries on the next open.
            prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        } catch (e: Exception) {
            Log.w("MainActivity", "Battery exemption prompt failed: ${e.message}")
        }
    }
}
