package edu.scut.livetranscript

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Participant(val name: String, val embedding: FloatArray)
data class Utterance(val speaker: String, val text: String, val timestamp: Long, val id: Long = 0)

class LocalStore(context: Context) : SQLiteOpenHelper(context, "live_transcript.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE participants (name TEXT PRIMARY KEY, embedding BLOB NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE utterances (id INTEGER PRIMARY KEY AUTOINCREMENT, speaker TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE voiceprints (name TEXT PRIMARY KEY, embedding BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS voiceprints (name TEXT PRIMARY KEY, embedding BLOB NOT NULL)")
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
        val rows = readableDatabase.rawQuery("SELECT name, embedding FROM participants ORDER BY name", null)
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Participant(cursor.getString(0), cursor.getBlob(1).toFloats()))
        } }
    }

    @Synchronized fun renameParticipant(oldName: String, newName: String): Boolean {
        val values = ContentValues().apply {
            put("name", newName)
            put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val changed = db.update("participants", values, "name = ?", arrayOf(oldName)) > 0
            if (changed) db.update("voiceprints", ContentValues().apply { put("name", newName) }, "name = ?", arrayOf(oldName))
            db.setTransactionSuccessful()
            changed
        } finally { db.endTransaction() }
    }

    @Synchronized fun deleteParticipant(name: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("voiceprints", "name = ?", arrayOf(name))
            val changed = db.delete("participants", "name = ?", arrayOf(name)) > 0
            db.setTransactionSuccessful()
            changed
        } finally { db.endTransaction() }
    }

    @Synchronized fun addUtterance(speaker: String, text: String): Utterance {
        val item = Utterance(speaker, text, System.currentTimeMillis())
        val values = ContentValues().apply {
            put("speaker", item.speaker); put("text", item.text); put("timestamp", item.timestamp)
        }
        writableDatabase.insert("utterances", null, values)
        return item
    }

    @Synchronized fun recentUtterances(): List<Utterance> {
        val rows = readableDatabase.rawQuery("SELECT speaker, text, timestamp, id FROM utterances ORDER BY id DESC", null)
        return rows.use { cursor -> buildList {
            while (cursor.moveToNext()) add(Utterance(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)))
        } }
    }

    @Synchronized fun updateUtterance(id: Long, text: String): Boolean {
        require(text.isNotBlank())
        return writableDatabase.update("utterances", ContentValues().apply { put("text", text.trim()) }, "id = ?", arrayOf(id.toString())) == 1
    }

    @Synchronized fun saveVoiceprint(name: String, vector: FloatArray) {
        require(VoiceprintMatcher.valid(vector))
        writableDatabase.insertWithOnConflict("voiceprints", null, ContentValues().apply {
            put("name", name); put("embedding", vector.toBytes())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun voiceprints(): Map<String, FloatArray> = readableDatabase.rawQuery(
        "SELECT v.name, v.embedding FROM voiceprints v JOIN participants p ON p.name = v.name", null
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1).toFloats()) } }

    @Synchronized fun clearVoiceprints() = writableDatabase.delete("voiceprints", null, null)

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
