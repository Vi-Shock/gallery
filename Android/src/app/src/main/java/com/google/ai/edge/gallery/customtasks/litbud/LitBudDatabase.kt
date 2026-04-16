/*
 * LitBud — Offline AI Reading Tutor for Children
 * Apache 2.0 License (same as Google AI Edge Gallery fork)
 */

package com.google.ai.edge.gallery.customtasks.litbud

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

/**
 * One row per reading passage attempt.
 * [struggledWords] is a JSON array string, e.g. ["fragment","enormous"]
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val accuracyPercent: Float,
    val wordsPerMinute: Float,
    val struggledWords: String,
)

/**
 * One row per reading session (a session = one "Read a Book" flow start to finish).
 * [newVocabulary] is a JSON array string.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,
    val durationSeconds: Int,
    val overallAccuracy: Float,
    val newVocabulary: String,
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface ProgressDao {
    @Insert
    fun insert(entry: ProgressEntity)

    @Query("SELECT * FROM progress ORDER BY timestamp ASC")
    fun allEntries(): Flow<List<ProgressEntity>>
}

@Dao
interface SessionDao {
    @Insert
    fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY id ASC")
    fun allSessions(): Flow<List<SessionEntity>>
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [ProgressEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LitBudDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: LitBudDatabase? = null

        fun getInstance(context: Context): LitBudDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LitBudDatabase::class.java,
                    "litbud.db",
                ).build().also { INSTANCE = it }
            }
    }
}
