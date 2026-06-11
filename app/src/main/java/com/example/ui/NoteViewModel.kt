package com.example.ui

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.reminders.ReminderReceiver
import com.example.reminders.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class NoteViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: NoteRepository
    val prefs = Prefs(application)

    // Flows
    val allActiveNotes: StateFlow<List<Note>>
    val archivedNotes: StateFlow<List<Note>>
    val recycleBinNotes: StateFlow<List<Note>>
    val allCategories: StateFlow<List<String>>

    // Filtering, Sorting and Searching states
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory

    private val _selectedTag = MutableStateFlow("")
    val selectedTag: StateFlow<String> = _selectedTag

    private val _sortOrder = MutableStateFlow("modified_desc") // modified_desc, modified_asc, title_asc, title_desc
    val sortOrder: StateFlow<String> = _sortOrder

    // Filtered & Sorted notes
    val filteredNotes: StateFlow<List<Note>>

    // Undo/Redo Stacks (Simplified per-session cache for Editor)
    private var undoStack = java.util.Stack<NoteState>()
    private var redoStack = java.util.Stack<NoteState>()

    data class NoteState(val title: String, val content: String)

    init {
        val noteDao = AppDatabase.getDatabase(application).noteDao()
        repository = NoteRepository(noteDao)

        allActiveNotes = repository.allActiveNotes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        archivedNotes = repository.archivedNotes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        recycleBinNotes = repository.recycleBinNotes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        allCategories = repository.allCategories.stateIn(viewModelScope, SharingStarted.Lazily, listOf("General", "Work", "Personal"))

        // Combine filter states to produce live feed of notes
        filteredNotes = combine(
            allActiveNotes,
            _searchQuery,
            _selectedCategory,
            _selectedTag,
            _sortOrder
        ) { notes, query, category, tag, sort ->
            var list = notes

            // Search
            if (query.isNotEmpty()) {
                list = list.filter {
                    it.title.contains(query, ignoreCase = true) || 
                    it.content.contains(query, ignoreCase = true) ||
                    it.tags.contains(query, ignoreCase = true)
                }
            }

            // Category Filter
            if (category != "All") {
                list = list.filter { it.category == category }
            }

            // Tag Filter
            if (tag.isNotEmpty()) {
                list = list.filter { it.tags.contains(tag, ignoreCase = true) }
            }

            // Sorting
            when (sort) {
                "modified_desc" -> list.sortedByDescending { it.lastModified }
                "modified_asc" -> list.sortedBy { it.lastModified }
                "title_asc" -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                "title_desc" -> list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
                else -> list
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedCategory(cat: String) {
        _selectedCategory.value = cat
    }

    fun setSelectedTag(tag: String) {
        _selectedTag.value = tag
    }

    fun setSortOrder(order: String) {
        _sortOrder.value = order
    }

    // --- Database CRUD Actions ---

    fun saveNote(
        id: Long,
        title: String,
        content: String,
        isChecklist: Boolean,
        checklistJson: String,
        category: String,
        tags: String,
        reminderTime: Long,
        reminderRepeat: String,
        isHighPriority: Boolean,
        attachmentPath: String? = null,
        attachmentType: String? = null,
        isLocked: Boolean = false,
        passwordHash: String? = null,
        onSuccess: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val words = if (content.isEmpty()) 0 else content.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
            val chars = content.length
            val readTime = (words / 200).coerceAtLeast(1)

            val existingNote = if (id != 0L) repository.getNoteById(id) else null
            
            val noteToSave = Note(
                id = id,
                title = title.ifEmpty { "Untitled" },
                content = content,
                isChecklist = isChecklist,
                checklistJson = checklistJson,
                isPinned = existingNote?.isPinned ?: false,
                isFavorite = existingNote?.isFavorite ?: false,
                isArchived = existingNote?.isArchived ?: false,
                isDeleted = existingNote?.isDeleted ?: false,
                category = category,
                tags = tags,
                reminderTime = reminderTime,
                reminderRepeat = reminderRepeat,
                isReminderHighPriority = isHighPriority,
                isReminderDismissed = false,
                attachmentPath = attachmentPath ?: existingNote?.attachmentPath,
                attachmentType = attachmentType ?: existingNote?.attachmentType,
                isMarkdown = existingNote?.isMarkdown ?: false,
                wordCount = words,
                charCount = chars,
                readingTimeMinutes = readTime,
                isLocked = isLocked,
                passwordHash = passwordHash ?: existingNote?.passwordHash,
                lastModified = System.currentTimeMillis()
            )

            val savedId = repository.insertNote(noteToSave)
            
            // Re-schedule alarm if future reminder exists
            if (reminderTime > System.currentTimeMillis()) {
                scheduleAlarm(savedId, title, content, reminderTime)
            } else if (reminderTime == 0L && existingNote != null && existingNote.reminderTime > 0L) {
                cancelAlarm(id)
            }
            
            onSuccess(if (id == 0L) savedId else id)
        }
    }

    fun togglePin(note: Note) {
        viewModelScope.launch {
            repository.updateNote(note.copy(isPinned = !note.isPinned, lastModified = System.currentTimeMillis()))
        }
    }

    fun toggleFavorite(note: Note) {
        viewModelScope.launch {
            repository.updateNote(note.copy(isFavorite = !note.isFavorite, lastModified = System.currentTimeMillis()))
        }
    }

    fun moveToRecycleBin(note: Note) {
        viewModelScope.launch {
            repository.updateNote(note.copy(isDeleted = true, isPinned = false, lastModified = System.currentTimeMillis()))
            cancelAlarm(note.id)
        }
    }

    fun restoreFromRecycleBin(note: Note) {
        viewModelScope.launch {
            repository.updateNote(note.copy(isDeleted = false, lastModified = System.currentTimeMillis()))
            if (note.reminderTime > System.currentTimeMillis()) {
                scheduleAlarm(note.id, note.title, note.content, note.reminderTime)
            }
        }
    }

    fun toggleArchive(note: Note) {
        viewModelScope.launch {
            repository.updateNote(note.copy(isArchived = !note.isArchived, isPinned = false, lastModified = System.currentTimeMillis()))
        }
    }

    fun duplicateNote(note: Note) {
        viewModelScope.launch {
            val duplicate = note.copy(
                id = 0L,
                title = "${note.title} (Copy)",
                lastModified = System.currentTimeMillis()
            )
            repository.insertNote(duplicate)
        }
    }

    fun deleteNotePermanently(note: Note) {
        viewModelScope.launch {
            repository.deleteNote(note)
            cancelAlarm(note.id)
        }
    }

    fun emptyRecycleBin() {
        viewModelScope.launch {
            // Cancel all alarms in recycle bin first
            repository.recycleBinNotes.first().forEach { cancelAlarm(it.id) }
            repository.emptyRecycleBin()
        }
    }

    // --- Undo/Redo Engine for Editor ---
    fun clearEditorCache() {
        undoStack.clear()
        redoStack.clear()
    }

    fun pushEditorState(title: String, content: String) {
        if (undoStack.isNotEmpty()) {
            val topState = undoStack.peek()
            if (topState.title == title && topState.content == content) return
        }
        undoStack.push(NoteState(title, content))
        redoStack.clear()
    }

    fun performUndo(): NoteState? {
        if (undoStack.size < 2) return null // Must keep at least initial state
        val currentState = undoStack.pop()
        redoStack.push(currentState)
        return undoStack.peek()
    }

    fun performRedo(): NoteState? {
        if (redoStack.isEmpty()) return null
        val state = redoStack.pop()
        undoStack.push(state)
        return state
    }

    // --- Alarm manager integration ---
    private fun scheduleAlarm(noteId: Long, title: String, content: String, triggerTime: Long) {
        val context = getApplication<Application>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderService.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderService.EXTRA_NOTE_TITLE, title)
            putExtra(ReminderService.EXTRA_NOTE_CONTENT, content)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun cancelAlarm(noteId: Long) {
        val context = getApplication<Application>()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    // --- Local Backup & Restore Engine ---
    fun backupDatabaseLocal(onResult: (Boolean, String) -> Unit) {
        try {
            val dbName = "notenova_database"
            val dbFile: File = getApplication<Application>().getDatabasePath(dbName)
            if (!dbFile.exists()) {
                onResult(false, "Database doesn't exist yet! Save some notes first.")
                return
            }
            val backupDir = File(getApplication<Application>().getExternalFilesDir(null), "Backup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val backupFile = File(backupDir, "${dbName}_backup.sqlite")
            
            val src = FileInputStream(dbFile).channel
            val dst = FileOutputStream(backupFile).channel
            dst.transferFrom(src, 0, src.size())
            src.close()
            dst.close()

            // Also copy WAL and SHM files if they exist for perfect integrity
            val walFile = File(dbFile.path + "-wal")
            if (walFile.exists()) {
                val walBackup = File(backupDir, "${dbName}_backup.sqlite-wal")
                val walSrc = FileInputStream(walFile).channel
                val walDst = FileOutputStream(walBackup).channel
                walDst.transferFrom(walSrc, 0, walSrc.size())
                walSrc.close()
                walDst.close()
            }

            val shmFile = File(dbFile.path + "-shm")
            if (shmFile.exists()) {
                val shmBackup = File(backupDir, "${dbName}_backup.sqlite-shm")
                val shmSrc = FileInputStream(shmFile).channel
                val shmDst = FileOutputStream(shmBackup).channel
                shmDst.transferFrom(shmSrc, 0, shmSrc.size())
                shmSrc.close()
                shmDst.close()
            }

            onResult(true, "Backup saved to: ${backupFile.absolutePath}")
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(false, "Backup failed: ${e.message}")
        }
    }

    fun restoreDatabaseLocal(onResult: (Boolean, String) -> Unit) {
        try {
            val dbName = "notenova_database"
            val backupDir = File(getApplication<Application>().getExternalFilesDir(null), "Backup")
            val backupFile = File(backupDir, "${dbName}_backup.sqlite")
            if (!backupFile.exists()) {
                onResult(false, "No backup file found yet! Back up some files first.")
                return
            }

            // Close DB before restoring
            AppDatabase.getDatabase(getApplication()).close()

            val dbFile: File = getApplication<Application>().getDatabasePath(dbName)
            val src = FileInputStream(backupFile).channel
            val dst = FileOutputStream(dbFile).channel
            dst.transferFrom(src, 0, src.size())
            src.close()
            dst.close()

            val walBackup = File(backupDir, "${dbName}_backup.sqlite-wal")
            if (walBackup.exists()) {
                val walFile = File(dbFile.path + "-wal")
                val walSrc = FileInputStream(walBackup).channel
                val walDst = FileOutputStream(walFile).channel
                walDst.transferFrom(walSrc, 0, walSrc.size())
                walSrc.close()
                walDst.close()
            }

            val shmBackup = File(backupDir, "${dbName}_backup.sqlite-shm")
            if (shmBackup.exists()) {
                val shmFile = File(dbFile.path + "-shm")
                val shmSrc = FileInputStream(shmBackup).channel
                val shmDst = FileOutputStream(shmFile).channel
                shmDst.transferFrom(shmSrc, 0, shmSrc.size())
                shmSrc.close()
                shmDst.close()
            }

            onResult(true, "Database restored successfully. Restated interface to load changes.")
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(false, "Restore failed: ${e.message}")
        }
    }
}
