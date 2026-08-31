package com.lazy.smsrelay.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.lazy.smsrelay.core.Engine
import com.lazy.smsrelay.data.PendingStore
import com.lazy.smsrelay.data.Prefs
import com.lazy.smsrelay.service.RelayService
import java.util.concurrent.TimeUnit

/**
 * 兜底任务。三条职责：
 *  1. 消费 outbox 里因为断网/失败积压的待发项；
 *  2. 尝试把前台服务拉回来（Worker 里调用 startForegroundService 不一定成功，
 *     但配合「用户已关闭电池优化」的豁免条件，成功率不低）；
 *  3. 作为进程被彻底杀死后系统唯一的周期性唤醒点。
 */
class RetryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            if (PendingStore.isUnlocked(ctx)) {
                Engine.flush(ctx, limit = 30)
            }
            if (Prefs.serviceEnabled(ctx)) {
                RelayService.tryStart(ctx)
            }
            Result.success()
        } catch (t: Throwable) {
            // 返回 retry 让 WorkManager 自己退避，别把异常抛给框架
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "relay_retry"

        fun enqueue(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}
