package com.lazy.smsrelay.service

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.core.InboxScanner
import com.lazy.smsrelay.data.Db
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.receiver.SmsReceiver
import com.lazy.smsrelay.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻前台服务。它的价值不在于「干活」，而在于让进程成为一个「有身份的公民」：
 *
 *  - 只要它在，进程就不会被判定为 cached，短信广播能稳定送达；
 *  - 前台服务 + 常驻通知，是澎湃OS 上唯一相对体面的免死金牌；
 *  - 它被杀 ≠ 短信丢失：所有内容都在 outbox 里，下次启动会重放。
 *
 * foregroundServiceType = remoteMessaging（设备间消息转发）是语义最贴合的类型：
 * 比 specialUse 更容易通过应用商店审核，也不需要额外的 Play 政策声明。
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var dynamicReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        startAsForeground()

        // 动态注册一份短信广播：与静态注册双保险。
        // 某些 ROM 在进程已存活时会优先走动态接收器，静态那份反而被延迟。
        runCatching {
            val r = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            ContextCompat.registerReceiver(
                this, r, filter,
                ContextCompat.RECEIVER_EXPORTED // 系统广播，需要 exported
            )
            dynamicReceiver = r
        }

        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startAsForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_STATUS,
                Notifications.status(this, statusText()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                } else {
                    0
                }
            )
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    if (Prefs.serviceEnabled(this@RelayService) && PendingStore.isUnlocked(this@RelayService)) {
                        // 解锁后补发 Direct Boot 期间攒下的短信
                        val stored = PendingStore.drain(this@RelayService)
                        for (ev in stored) Engine.onSms(this@RelayService, ev)

                        Engine.flush(this@RelayService, limit = 20)

                        // 兜底扫描：每 15 分钟一次，捞回广播被 ROM 吞掉的短信
                        val minutes = Prefs.rescanMinutes(this@RelayService)
                        val everyTicks = (minutes * 60_000L / TICK_MS).coerceAtLeast(1)
                        if (Prefs.inboxRescan(this@RelayService) && tick % everyTicks == 0L) {
                            InboxScanner.scan(this@RelayService, minutes)
                            Engine.flush(this@RelayService, limit = 20)
                        }

                        refreshNotification()
                    }
                } catch (t: Throwable) {
                    // 循环里绝不让异常逃逸，否则服务变成空壳
                }
                tick++
                delay(TICK_MS)
            }
        }
    }

    private fun refreshNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(Notifications.ID_STATUS, Notifications.status(this, statusText()))
        }
    }

    private fun statusText(): String {
        if (!PendingStore.isUnlocked(this)) return "设备未解锁，解锁后自动补发"
        val last = Prefs.lastSuccessAt(this)
        val pending = runCatching { Db.get(this).pendingCount() }.getOrDefault(0)
        val lastStr = if (last == 0L) "暂无" else TIME_FMT.format(Date(last))
        val pendingStr = if (pending > 0) " · 待发 $pending 条" else ""
        return "已转发 ${Prefs.totalForwarded(this)} 条 · 最近 $lastStr$pendingStr"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // stopWithTask=false 已经保证清后台时不带走服务，这里再补一脚保险：
        // 某些 ROM 会无视清单属性，直接给一次重启广播。
        runCatching {
            sendBroadcast(
                Intent(com.lazy.smsrelay.receiver.LifecycleReceiver.ACTION_RESTART)
                    .setPackage(packageName)
            )
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        runCatching { dynamicReceiver?.let { unregisterReceiver(it) } }
        super.onDestroy()
        // 异常销毁时尝试自救（正常 stopService 会把 enabled 置为 false）
        if (Prefs.serviceEnabled(this)) {
            runCatching {
                sendBroadcast(
                    Intent(com.lazy.smsrelay.receiver.LifecycleReceiver.ACTION_RESTART)
                        .setPackage(packageName)
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TICK_MS = 30_000L
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.CHINA)

        /**
         * 服务是否真的还活着。
         *
         * 这里刻意不用「onCreate 置 true / onDestroy 置 false」的布尔标志位：
         * 进程被系统杀掉、被 ROM 强杀、被一键清理时，onDestroy **根本不会执行**，
         * 标志位会永远卡在 true，于是所有自恢复逻辑都会以为「服务还在」而跳过重启 ——
         * 这是一个非常隐蔽、且在小米系 ROM 上必现的坑。
         *
         * API 26 起 getRunningServices() 只返回调用方自己的服务，正好够用。
         */
        @Suppress("DEPRECATION")
        fun isAlive(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            return runCatching {
                am.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == RelayService::class.java.name }
            }.getOrDefault(false)
        }

        /**
         * 尝试启动服务。
         * Android 12+ 起「后台启动前台服务」受限，允许的场景包括：
         *  - 用户关闭了本应用的电池优化（我们会在保活引导里强制要求）
         *  - BOOT_COMPLETED / MY_PACKAGE_REPLACED 等豁免广播
         * 除此之外一律会抛 ForegroundServiceStartNotAllowedException，
         * 所以这里必须 try/catch，不能让调用方崩掉。
         */
        fun tryStart(ctx: Context) {
            if (!Prefs.serviceEnabled(ctx)) return
            if (isAlive(ctx)) return
            runCatching {
                val i = Intent(ctx, RelayService::class.java)
                ContextCompat.startForegroundService(ctx, i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, RelayService::class.java)) }
        }
    }
}
