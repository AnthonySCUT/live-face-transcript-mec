package edu.scut.livetranscript

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Participant(val name: String, val embedding: FloatArray, val voiceEmbedding: FloatArray? = null)
data class Utterance(val id: Long, val speaker: String, val text: String, val timestamp: Long)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "live_transcript.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE participants (name TEXT PRIMARY KEY, embedding BLOB NOT NULL, voice_embedding BLOB, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE utterances (id INTEGER PRIMARY KEY AUTOINCREMENT, speaker TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE participants ADD COLUMN voice_embedding BLOB")
    }

    @Synchronized fun saveParticipant(name: String, embedding: FloatArray) {
        val values = ContentValues().apply {
            put("name", name)
            put("embedding", embedding.toBytes())
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("participants", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun participants(): List<Participant> {
        val rows = readableDatabase.rawQuery("SELECT name, embedding, voice_embedding FROM participants ORDER BY name", null)
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Participant(cursor.getString(0), cursor.getBlob(1).toFloats(), if (cursor.isNull(2)) null else cursor.getBlob(2).toFloats()))
        } }
    }

    @Synchronized fun saveVoiceprint(name: String, embedding: FloatArray) {
        val values = ContentValues().apply { put("voice_embedding", embedding.toBytes()) }
        writableDatabase.update("participants", values, "name = ?", arrayOf(name))
    }

    @Synchronized fun renameParticipant(oldName: String, newName: String): Boolean {
        val values = ContentValues().apply {
            put("name", newName)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("participants", values, "name = ?", arrayOf(oldName)) > 0
    }

    @Synchronized fun deleteParticipant(name: String): Boolean =
        writableDatabase.delete("participants", "name = ?", arrayOf(name)) > 0

    @Synchronized fun addUtterance(speaker: String, text: String): Utterance {
        val item = Utterance(-1L, speaker, text, System.currentTimeMillis())
        val values = ContentValues().apply {
            put("speaker", item.speaker); put("text", item.text); put("timestamp", item.timestamp)
        }
        return item.copy(id = writableDatabase.insert("utterances", null, values))
    }

    @Synchronized fun updateUtterance(id: Long, text: String): Boolean {
        val values = ContentValues().apply { put("text", text) }
        return writableDatabase.update("utterances", values, "id = ?", arrayOf(id.toString())) > 0
    }

    @Synchronized fun recentUtterances(): List<Utterance> {
        val rows = readableDatabase.rawQuery("SELECT id, speaker, text, timestamp FROM utterances ORDER BY id DESC", null)
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Utterance(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
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
