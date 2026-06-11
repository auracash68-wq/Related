package com.example.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {
    val allActiveNotes: Flow<List<Note>> = noteDao.getAllActiveNotes()
    val archivedNotes: Flow<List<Note>> = noteDao.getArchivedNotes()
    val recycleBinNotes: Flow<List<Note>> = noteDao.getRecycleBinNotes()
    val activeReminders: Flow<List<Note>> = noteDao.getActiveReminders()
    val allCategories: Flow<List<String>> = noteDao.getAllCategories()

    suspend fun getNoteById(id: Long): Note? {
        return noteDao.getNoteById(id)
    }

    fun getNoteByIdFlow(id: Long): Flow<Note?> {
        return noteDao.getNoteByIdFlow(id)
    }

    suspend fun insertNote(note: Note): Long {
        return noteDao.insertNote(note)
    }

    suspend fun updateNote(note: Note) {
        noteDao.updateNote(note)
    }

    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
    }

    suspend fun emptyRecycleBin() {
        noteDao.emptyRecycleBin()
    }
}
