package com.lazy.smsrelay

import android.app.Application
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.service.RelayService
import com.lazy.smsrelay.util.Notifications
import com.lazy.smsrelay.worker.RetryWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        RetryWorker.enqueue(this)

        // 进程每次被拉起都尝试恢复服务。
        // 这里刻意不直接 startForegroundService：Application.onCreate 处于后台状态，
        // 在没拿到「电池优化豁免」的设备上会抛 ForegroundServiceStartNotAllowedException。
        // 交给 RelayService.tryStart 统一吞掉异常更稳妥。
        if (Prefs.serviceEnabled(this) && PendingStore.isUnlocked(this)) {
            RelayService.tryStart(this)
        }
    }
}
