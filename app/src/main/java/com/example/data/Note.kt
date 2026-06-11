package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String = "",
    val content: String = "",
    val isChecklist: Boolean = false,
    val checklistJson: String = "[]", // Stores checklist items as JSON: [{"text":"item", "checked":true}]
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false, // True means in Recycle Bin
    val category: String = "General", // General, Work, Personal, etc.
    val tags: String = "", // Comma-separated tags
    
    // Reminders
    val reminderTime: Long = 0L, // Unix epoch milliseconds, 0 if none
    val reminderRepeat: String = "none", // none, daily, weekly, monthly, yearly
    val isReminderHighPriority: Boolean = false, // Alarm & play music vs silent notification
    val isReminderDismissed: Boolean = false,
    
    // Attachments
    val attachmentPath: String? = null,
    val attachmentType: String? = null, // "image", "audio", "pdf", "file"
    
    // Formatting & Word stats
    val isMarkdown: Boolean = false,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val readingTimeMinutes: Int = 0,
    
    // Security
    val isLocked: Boolean = false,
    val passwordHash: String? = null,
    
    val lastModified: Long = System.currentTimeMillis()
)
