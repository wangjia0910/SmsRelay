package com.lazy.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.service.RelayService
import com.lazy.smsrelay.worker.RetryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 生命周期接收器 —— 拉活的第一责任人。
 *
 * BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 是官方明确允许的
 * 「后台启动前台服务」豁免场景，所以在国产 ROM 上这是唯一站得住脚的自动拉起入口。
 * （别再用监听网络变化、解锁广播去拉活了：Android 7+ 静态注册基本收不到，
 *   而且这类行为在澎湃OS 的「应用行为记录」里会被打点。）
 */
class LifecycleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val appCtx = context.applicationContext

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == ACTION_RESTART
        ) {
            // 解锁前先别碰凭据加密存储，会直接抛异常
            val unlocked = PendingStore.isUnlocked(appCtx)
            if (!unlocked) {
                RelayService.tryStart(appCtx)
                return
            }

            RelayService.tryStart(appCtx)
            RetryWorker.enqueue(appCtx)

            if (!Prefs.serviceEnabled(appCtx)) return

            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // 重启后未解锁期间攒下的短信，在这里补发
                    val stored = PendingStore.drain(appCtx)
                    for (ev in stored) Engine.onSms(appCtx, ev)
                    Engine.flush(appCtx, limit = 30)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_RESTART = "com.lazy.smsrelay.ACTION_RESTART"

        fun sendRestart(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_RESTART).setPackage(context.packageName)
            )
        }
    }
}
