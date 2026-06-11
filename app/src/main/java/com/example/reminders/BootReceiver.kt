package com.example.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(appContext)
                // Retrieve all notes with future scheduled reminders that haven't been dismissed
                val now = System.currentTimeMillis()
                val activeNotes = db.noteDao().getActiveReminders()
                
                // ActiveReminders returns Flow, but here we can evaluate a one-shot query from Database or collect first element
                // Since Room DAO returns custom flows, we can easily query directly or fetch from SQLite
                // Let's iterate or collect first
                db.noteDao().getAllActiveNotes().collect { notes ->
                    val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    for (note in notes) {
                        if (note.reminderTime > now && !note.isReminderDismissed) {
                            val alarmIntent = Intent(appContext, ReminderReceiver::class.java).apply {
                                putExtra(ReminderService.EXTRA_NOTE_ID, note.id)
                                putExtra(ReminderService.EXTRA_NOTE_TITLE, note.title)
                                putExtra(ReminderService.EXTRA_NOTE_CONTENT, note.content)
                            }
                            val pendingIntent = PendingIntent.getBroadcast(
                                appContext,
                                note.id.toInt(),
                                alarmIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    note.reminderTime,
                                    pendingIntent
                                )
                            } else {
                                alarmManager.setExact(
                                    AlarmManager.RTC_WAKEUP,
                                    note.reminderTime,
                                    pendingIntent
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
