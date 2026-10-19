package edu.scut.livetranscript

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Participant(val name: String, val embedding: FloatArray)
data class Utterance(val speaker: String, val text: String, val timestamp: Long)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "live_transcript.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE participants (name TEXT PRIMARY KEY, embedding BLOB NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE utterances (id INTEGER PRIMARY KEY AUTOINCREMENT, speaker TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun saveParticipant(name: String, embedding: FloatArray) {
        val values = ContentValues().apply {
            put("name", name)
            put("embedding", embedding.toBytes())
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("participants", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun participants(): List<Participant> {
        val rows = readableDatabase.rawQuery("SELECT name, embedding FROM participants ORDER BY name", null)
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Participant(cursor.getString(0), cursor.getBlob(1).toFloats()))
        } }
    }

    @Synchronized fun addUtterance(speaker: String, text: String): Utterance {
        val item = Utterance(speaker, text, System.currentTimeMillis())
        val values = ContentValues().apply {
            put("speaker", item.speaker); put("text", item.text); put("timestamp", item.timestamp)
        }
        writableDatabase.insert("utterances", null, values)
        return item
    }

    @Synchronized fun recentUtterances(limit: Int = 20): List<Utterance> {
        val rows = readableDatabase.rawQuery(
            "SELECT speaker, text, timestamp FROM utterances ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Utterance(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
        } }
    }

    @Synchronized fun clearUtterances() = writableDatabase.delete("utterances", null, null)

    private fun FloatArray.toBytes() = ByteArray(size * 4).also { bytes ->
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        forEach(buffer::putFloat)
    }

    private fun ByteArray.toFloats() = FloatArray(size / 4).also { values ->
        val buffer = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in values.indices) values[i] = buffer.float
    }
}
