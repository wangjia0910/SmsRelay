package com.lazy.smsrelay.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地落库三张表：
 *  - logs：转发流水，UI 展示与排查用
 *  - outbox：待发队列。广播到达后先入库再发送，任何一步被杀都能重放，
 *            这是「不丢短信」的唯一可靠保证（内存队列在澎湃OS 上活不过 5 分钟）
 *  - dedup：短时间去重，防止同一条短信被广播 + 通知 + 兜底扫描重复转发
 */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "relay.db", null, DB_VERSION) {

    companion object {
        private const val DB_VERSION = 2
        private val ref = AtomicReference<Db?>(null)

        fun get(ctx: Context): Db {
            ref.get()?.let { return it }
            synchronized(this) {
                ref.get()?.let { return it }
                val db = Db(ctx.applicationContext)
                ref.set(db)
                return db
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                sender TEXT,
                body TEXT,
                sim INTEGER,
                source TEXT,
                status TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                channel_id TEXT NOT NULL,
                text TEXT NOT NULL,
                sender TEXT,
                body TEXT,
                sim INTEGER,
                source TEXT,
                rule TEXT,
                tries INTEGER DEFAULT 0,
                next_at INTEGER DEFAULT 0,
                last_error TEXT,
                is_otp INTEGER DEFAULT 0,
                expires_at INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE dedup (
                h TEXT PRIMARY KEY,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_logs_ts ON logs(ts DESC)")
        db.execSQL("CREATE INDEX idx_outbox_next ON outbox(next_at)")
        // 验证码优先队列：先按 is_otp 降序，再按入库顺序，保证验证码不被积压的旧消息挡住
        db.execSQL("CREATE INDEX idx_outbox_prio ON outbox(is_otp, next_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN is_otp INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE outbox ADD COLUMN expires_at INTEGER DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_prio ON outbox(is_otp, next_at)")
        }
    }

    /* ------------------------------- 日志 ------------------------------- */

    fun insertLog(ev: SmsEvent, status: String, detail: String = ""): Long {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("sender", ev.from)
            put("body", ev.body.take(500))
            put("sim", ev.simSlot)
            put("source", ev.source)
            put("status", status)
            put("detail", detail)
        }
        return writableDatabase.insert("logs", null, cv)
    }

    fun recentLogs(limit: Int = 200): List<LogItem> {
        val out = ArrayList<LogItem>(limit)
        readableDatabase.query(
            "logs", null, null, null, null, null, "id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += LogItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                    sender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
                    body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
                    sim = c.getInt(c.getColumnIndexOrThrow("sim")),
                    source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
                    status = c.getString(c.getColumnIndexOrThrow("status")) ?: "",
                    detail = c.getString(c.getColumnIndexOrThrow("detail")) ?: ""
                )
            }
        }
        return out
    }

    fun clearLogs() {
        writableDatabase.delete("logs", null, null)
    }

    /* ------------------------------ 待发队列 ------------------------------ */

    /**
     * @param isOtp   是否为验证码短信。验证码会被插到队列最前面，
     *                避免被前面积压的失败旧消息挡住（验证码晚 5 分钟就没用了）
     * @param ttlMs   存活时间。超过该时间仍未发出的验证码会被直接丢弃，
     *                因为「补发一个 20 分钟前的验证码」是噪音，不是功能
     */
    fun enqueue(
        channelId: String,
        text: String,
        ev: SmsEvent,
        rule: String,
        isOtp: Boolean = false,
        ttlMs: Long = 0
    ): Long {
        val cv = ContentValues().apply {
            put("ts", ev.timestamp)
            put("channel_id", channelId)
            put("text", text)
            put("sender", ev.from)
            put("body", ev.body)
            put("sim", ev.simSlot)
            put("source", ev.source)
            put("rule", rule)
            put("tries", 0)
            put("next_at", 0)
            put("last_error", "")
            put("is_otp", if (isOtp) 1 else 0)
            put("expires_at", if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0)
        }
        return writableDatabase.insert("outbox", null, cv)
    }

    /** 取待发项：验证码优先，其次按入库顺序 */
    fun pending(now: Long = System.currentTimeMillis(), limit: Int = 20): List<OutboxItem> {
        val out = ArrayList<OutboxItem>()
        readableDatabase.query(
            "outbox",
            null,
            "next_at <= ? AND (expires_at = 0 OR expires_at > ?)",
            arrayOf(now.toString(), now.toString()),
            null, null,
            "is_otp DESC, id ASC",
            limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += OutboxItem(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                    channelId = c.getString(c.getColumnIndexOrThrow("channel_id")),
                    text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
                    sender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
                    body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
                    simSlot = c.getInt(c.getColumnIndexOrThrow("sim")),
                    source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
                    rule = c.getString(c.getColumnIndexOrThrow("rule")) ?: "",
                    tries = c.getInt(c.getColumnIndexOrThrow("tries")),
                    nextAt = c.getLong(c.getColumnIndexOrThrow("next_at")),
                    lastError = c.getString(c.getColumnIndexOrThrow("last_error")) ?: "",
                    isOtp = c.getInt(c.getColumnIndexOrThrow("is_otp")) == 1
                )
            }
        }
        return out
    }

    /** 丢弃已过期的待发项（目前只有验证码会设置过期时间） */
    fun dropExpired(now: Long = System.currentTimeMillis()): Int =
        writableDatabase.delete("outbox", "expires_at > 0 AND expires_at <= ?", arrayOf(now.toString()))

    fun remove(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    /** 失败回退：指数退避 + 抖动，避免整机组同时重打服务端 */
    fun markRetry(id: Long, tries: Int, error: String) {
        val backoff = when (tries) {
            1 -> 30_000L
            2 -> 60_000L
            3 -> 300_000L
            4 -> 900_000L
            else -> 1_800_000L
        }
        val jitter = (Math.random() * 0.3 * backoff).toLong()
        val cv = ContentValues().apply {
            put("tries", tries)
            put("last_error", error.take(300))
            put("next_at", System.currentTimeMillis() + backoff + jitter)
        }
        writableDatabase.update("outbox", cv, "id = ?", arrayOf(id.toString()))
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /* ------------------------------- 去重 ------------------------------- */

    fun isDuplicate(hash: String, windowMs: Long): Boolean {
        if (windowMs <= 0) return false
        val deadline = System.currentTimeMillis() - windowMs
        readableDatabase.query("dedup", arrayOf("h"), "h = ? AND ts >= ?",
            arrayOf(hash, deadline.toString()), null, null, null).use { c ->
            return c.moveToFirst()
        }
    }

    fun putHash(hash: String, ts: Long) {
        writableDatabase.insertWithOnConflict(
            "dedup", null,
            ContentValues().apply {
                put("h", hash)
                put("ts", ts)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun prune(cutoff: Long) {
        writableDatabase.delete("dedup", "ts < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("logs", "ts < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("outbox", "ts < ?", arrayOf(cutoff.toString()))
    }
}
