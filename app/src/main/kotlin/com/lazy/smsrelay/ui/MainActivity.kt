package com.lazy.smsrelay.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.service.RelayService

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // 授权结果不做额外处理：保活页每次显示都会重新读状态
            val ok = result[Manifest.permission.RECEIVE_SMS] == true
            if (ok && Prefs.serviceEnabled(this) && PendingStore.isUnlocked(this)) {
                RelayService.tryStart(this)
            }
            recreate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RelayTheme {
                AppNav(
                    ctx = this,
                    onRequestSmsPermission = { requestSmsPermissions() }
                )
            }
        }
    }

    private fun requestSmsPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}
