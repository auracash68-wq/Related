package com.example.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val noteId = intent.getLongExtra(ReminderService.EXTRA_NOTE_ID, -1L)
        if (noteId == -1L) return

        val title = intent.getStringExtra(ReminderService.EXTRA_NOTE_TITLE) ?: "Reminder"
        val content = intent.getStringExtra(ReminderService.EXTRA_NOTE_CONTENT) ?: "A customized note reminder arrived."

        val serviceIntent = Intent(context, ReminderService::class.java).apply {
            putExtra(ReminderService.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderService.EXTRA_NOTE_TITLE, title)
            putExtra(ReminderService.EXTRA_NOTE_CONTENT, content)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
