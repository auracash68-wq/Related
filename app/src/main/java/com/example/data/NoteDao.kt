package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isArchived = 0 AND isDeleted = 0 ORDER BY isPinned DESC, lastModified DESC")
    fun getAllActiveNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getArchivedNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY lastModified DESC")
    fun getRecycleBinNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE reminderTime > 0 AND isReminderDismissed = 0 AND isDeleted = 0 ORDER BY reminderTime ASC")
    fun getActiveReminders(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun getNoteByIdFlow(id: Long): Flow<Note?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("DELETE FROM notes WHERE isDeleted = 1")
    suspend fun emptyRecycleBin()

    @Query("SELECT DISTINCT category FROM notes WHERE isDeleted = 0")
    fun getAllCategories(): Flow<List<String>>
}
