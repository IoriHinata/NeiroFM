package com.neirofm.broadcast

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class BufferedAudio(
    val id: String,
    val path: String,
    val title: String,
    val presenter: String,
    val kind: String,
    val durationMs: Long,
    val createdAt: Long
)

class AudioBufferStore(context: Context) : SQLiteOpenHelper(context, "neirofm_buffer.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE audio_segments (
            id TEXT PRIMARY KEY, path TEXT NOT NULL, title TEXT NOT NULL,
            presenter TEXT NOT NULL, kind TEXT NOT NULL, duration_ms INTEGER NOT NULL,
            created_at INTEGER NOT NULL, consumed INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX idx_audio_pending ON audio_segments(consumed, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun add(item: BufferedAudio) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO audio_segments(id,path,title,presenter,kind,duration_ms,created_at,consumed) VALUES(?,?,?,?,?,?,?,0)",
            arrayOf(item.id, item.path, item.title, item.presenter, item.kind, item.durationMs, item.createdAt)
        )
    }

    @Synchronized fun pending(): List<BufferedAudio> {
        val result = mutableListOf<BufferedAudio>()
        readableDatabase.rawQuery("SELECT id,path,title,presenter,kind,duration_ms,created_at FROM audio_segments WHERE consumed=0 ORDER BY created_at", null).use { c ->
            while (c.moveToNext()) result += BufferedAudio(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getLong(6))
        }
        return result.filter { File(it.path).isFile }
    }

    @Synchronized fun pendingDurationMs(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(duration_ms),0) FROM audio_segments WHERE consumed=0", null).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    @Synchronized fun markConsumed(id: String) {
        writableDatabase.execSQL("UPDATE audio_segments SET consumed=1 WHERE id=?", arrayOf(id))
    }

    @Synchronized fun removeOld(maxBytes: Long = 500L * 1024L * 1024L) {
        val consumed = readableDatabase.rawQuery("SELECT path FROM audio_segments WHERE consumed=1 ORDER BY created_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        consumed.forEach { File(it).delete() }
        writableDatabase.execSQL("DELETE FROM audio_segments WHERE consumed=1")
        // Never delete audio that has not aired, even when the cache limit is exceeded.
    }
}
