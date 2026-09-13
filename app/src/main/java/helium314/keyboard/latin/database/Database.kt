// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction
import helium314.keyboard.latin.utils.GestureDataDao
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.LogCatcher
import java.io.File

class Database private constructor(context: Context, name: String = NAME) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ClipboardDao.CREATE_TABLE)
        db.execSQL(PromptDao.CREATE_TABLE)
        onUpgrade(db, 0, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion <= 1) {
            db.execSQL(GestureDataDao.CREATE_TABLE)
        }
        if (oldVersion <= 2) {
            db.execSQL(ClipboardDao.ADD_FILE_COLUMN)
            db.execSQL(ClipboardDao.ADD_MIME_TYPE_COLUMN)
        }
        if (oldVersion <= 3) {
            db.execSQL(PromptDao.CREATE_TABLE)
        }
    }

    companion object {
        private val TAG = Database::class.java.simpleName
        private const val VERSION = 4
        const val NAME = "heliboard.db"
        private var instance: Database? = null
        fun getInstance(context: Context): Database {
            if (instance == null)
                instance = Database(context)
            return instance!!
        }

        // needs to be in sync with db version
        fun copyFromDb(file: File, context: Context) {
            if (!file.exists())
                return
            val otherDb = Database(context, file.name) // this upgrades the DB if necessary
            val clipDao = ClipboardDao.getInstance(context) // insert to dao because of cache
            val db = getInstance(context)

            try {
                db.writableDatabase.transaction {
                    if (clipDao == null) {
                        Log.e(TAG, "can't transfer clipboard data because ClipboardDao is null")
                    } else {
                        otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE FROM CLIPBOARD", null)
                            .use {
                                clipDao.clear()
                                while (it.moveToNext()) {
                                    clipDao.insertNewEntry(
                                        it.getLong(0),
                                        it.getInt(1) != 0,
                                        it.getStringOrNull(2),
                                        it.getStringOrNull(3),
                                        it.getStringOrNull(4)?.split("§"),
                                        null
                                    )
                                }
                            }
                    }
                    db.writableDatabase.execSQL("DELETE FROM GESTURE_DATA")
                    otherDb.readableDatabase.rawQuery("SELECT TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA FROM GESTURE_DATA", null)
                        .use { c ->
                            while (c.moveToNext()) {
                                execSQL("INSERT INTO GESTURE_DATA (TIMESTAMP, WORD, EXPORTED, SOURCE_ACTIVE, DATA) " +
                                    "VALUES (${c.getLong(0)},?,${c.getInt(2)},${c.getInt(3)},?)", arrayOf(c.getString(1), c.getString(4)))
                            }
                        }

                    // Restore Prompts table if present in otherDb
                    try {
                        PromptDao.ensureTableExists(db.writableDatabase)
                        val hasPrompts = otherDb.readableDatabase.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='${PromptDao.TABLE}'", null
                        ).use { it.moveToFirst() }
                        if (hasPrompts) {
                            otherDb.readableDatabase.rawQuery(
                                "SELECT TIMESTAMP, PINNED, TITLE, TEXT FROM ${PromptDao.TABLE}", null
                            ).use { c ->
                                db.writableDatabase.execSQL("DELETE FROM ${PromptDao.TABLE}")
                                while (c.moveToNext()) {
                                    val cv = ContentValues().apply {
                                        put(PromptDao.COLUMN_TIMESTAMP, c.getLong(0))
                                        put(PromptDao.COLUMN_PINNED, c.getInt(1))
                                        put(PromptDao.COLUMN_TITLE, c.getStringOrNull(2))
                                        put(PromptDao.COLUMN_TEXT, c.getStringOrNull(3))
                                    }
                                    db.writableDatabase.insert(PromptDao.TABLE, null, cv)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        LogCatcher.log('W', TAG, "Restore prompts skipped: ${t.message}")
                    }
                }
                // Reload in-memory PromptDao cache
                PromptDao.getInstance(context).reload()
            } finally {
                otherDb.close()
                file.delete()
            }
        }
    }
}
