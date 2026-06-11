package com.example.reminders

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    
    companion object {
        const val CHANNEL_ID = "notenova_reminders_channel"
        const val NOTIFICATION_ID = 8888
        
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        const val EXTRA_NOTE_CONTENT = "extra_note_content"
        
        const val ACTION_DISMISS = "com.example.reminders.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.example.reminders.ACTION_SNOOZE"

        @Volatile
        var isRinging = false
        var activeNoteId: Long = -1L
    }

    override fun onCreate() {
        super.onCreate()
        isRinging = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "Reminder"
        val noteContent = intent.getStringExtra(EXTRA_NOTE_CONTENT) ?: "You have an active task reminder."
        activeNoteId = noteId

        when (intent.action) {
            ACTION_DISMISS -> {
                dismissReminder(noteId)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                snoozeReminder(noteId, noteTitle, noteContent)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Create Channel description and build channel
        createNotificationChannel()

        // Intents for action items
        val dismissIntent = Intent(this, ReminderService::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, noteId.toInt() + 100, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, ReminderService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_NOTE_TITLE, noteTitle)
            putExtra(EXTRA_NOTE_CONTENT, noteContent)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this, noteId.toInt() + 200, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open specific App Note
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("note_id_to_open", noteId)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, noteId.toInt() + 300, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ NoteNova Pro: $noteTitle")
            .setContentText(noteContent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(openAppPendingIntent, true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(noteContent))
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Snooze (5m)", snoozePendingIntent)

        // Android 14+ needs foreground service type or standard foreground starting
        startForeground(NOTIFICATION_ID, builder.build())

        // Start playing ringtone audio
        startRinging()

        // Start vibrating continuously
        startVibrating()

        return START_STICKY
    }

    private fun startRinging() {
        try {
            val alertUri: Uri = Uri.parse("android.resource://$packageName/" + android.R.drawable.ic_lock_idle_alarm) // Using default or alarm uri
            val ringtoneUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, ringtoneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("ReminderService", "Failed to launch alarm ringtone", e)
            // Fallback play simple sound
            try {
                mediaPlayer = MediaPlayer.create(applicationContext, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)?.apply {
                    isLooping = true
                    start()
                }
            } catch (ex: Exception) {
                Log.e("ReminderService", "Fallback ringtone failed", ex)
            }
        }
    }

    private fun startVibrating() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Repeat index 0, -1 means no repeat
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400, 800, 400), 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 800, 400, 800, 400), 0)
            }
        }
    }

    private fun dismissReminder(noteId: Long) {
        if (noteId == -1L) return
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val note = db.noteDao().getNoteById(noteId)
            if (note != null) {
                db.noteDao().updateNote(note.copy(isReminderDismissed = true))
            }
        }
    }

    private fun snoozeReminder(noteId: Long, title: String, content: String) {
        if (noteId == -1L) return
        // Reschedule alarm 5 minutes later
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_NOTE_TITLE, title)
            putExtra(EXTRA_NOTE_CONTENT, content)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, noteId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + (5 * 60 * 1000) // 5 minutes snooze

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "NoteNova Reminders"
            val descriptionText = "Rings when a note's custom scheduled threshold reminder arrives"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                setSound(null, null) // Handled manually in foreground service
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRinging = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
