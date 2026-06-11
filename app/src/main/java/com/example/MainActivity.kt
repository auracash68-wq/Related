package com.example

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.Note
import com.example.databinding.ActivityMainBinding
import com.example.databinding.ItemChecklistBinding
import com.example.reminders.ReminderService
import com.example.ui.NotesListAdapter
import com.example.ui.NoteViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: NoteViewModel
    private lateinit var adapter: NotesListAdapter

    // Current open editor context
    private var currentEditingNoteId: Long = 0L
    private var isChecklistMode = false
    private var selectedReminderTimestamp: Long = 0L
    private var selectedAttachmentPath: String? = null
    private var selectedAttachmentType: String? = null
    private var temporaryPasscode = ""

    // Current visual folder section being displayed: "active", "archive", "bin", "reminders"
    private var currentSection = "active"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[NoteViewModel::class.java]

        // Handle Theme Preference On Start
        applyThemePref(viewModel.prefs.themeMode)

        // Setup App Lock UI block on startup if PIN is locked
        setupSecurityStartupLock()

        // Setup layouts & navigation events
        setupNotesListView()
        setupDrawerNavigation()
        setupCategoryPickers()
        setupSortSelector()
        setupSearchInput()
        setupEditorWorkspace()
        setupSettingsAndPremium()

        // Handle incoming note open intents (from notifications)
        handleNotificationDeepLink(intent)

        // Custom back presses to close editor gracefully and trigger auto-save
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutNoteEditorOverlay.visibility == View.VISIBLE) {
                    closeEditorAndSave()
                } else if (binding.layoutSettingsOverlay.visibility == View.VISIBLE) {
                    binding.layoutSettingsOverlay.visibility = View.GONE
                } else if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationDeepLink(intent)
    }

    private fun handleNotificationDeepLink(intent: Intent?) {
        if (intent == null) return
        val noteId = intent.getLongExtra("note_id_to_open", -1L)
        if (noteId != -1L) {
            // Dismiss active background alarm player
            if (ReminderService.isRinging) {
                val stopServiceIntent = Intent(this, ReminderService::class.java)
                stopService(stopServiceIntent)
                Toast.makeText(this, "Alarm dismissed successfully.", Toast.LENGTH_SHORT).show()
            }

            // Immediately query the note database
            lifecycleScope.launch {
                val dbNote = viewModel.allActiveNotes.value.find { it.id == noteId } 
                    ?: viewModel.archivedNotes.value.find { it.id == noteId }
                if (dbNote != null) {
                    // Mark as viewed/dismissed automatically
                    viewModel.saveNote(
                        id = dbNote.id,
                        title = dbNote.title,
                        content = dbNote.content,
                        isChecklist = dbNote.isChecklist,
                        checklistJson = dbNote.checklistJson,
                        category = dbNote.category,
                        tags = dbNote.tags,
                        reminderTime = dbNote.reminderTime,
                        reminderRepeat = dbNote.reminderRepeat,
                        isHighPriority = dbNote.isReminderHighPriority,
                        attachmentPath = dbNote.attachmentPath,
                        attachmentType = dbNote.attachmentType,
                        isLocked = dbNote.isLocked,
                        passwordHash = dbNote.passwordHash
                    )
                    openNoteForEditing(dbNote)
                }
            }
        }
    }

    private fun applyThemePref(theme: String) {
        when (theme) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // ==============================================
    // SECURITY PIN LOCK LOGIC
    // ==============================================
    private fun setupSecurityStartupLock() {
        val activePin = viewModel.prefs.appLockPin
        if (activePin != null) {
            binding.layoutAppLockOverlay.visibility = View.VISIBLE
            var pinBuffer = ""

            // Clear digits from previous buffers
            binding.txtPINDisplay.text = "◯  ◯  ◯  ◯"

            // Setup Dialpad click listeners
            val keyboardLayout = binding.layoutNumKeyboard
            for (i in 0 until keyboardLayout.childCount) {
                val view = keyboardLayout.getChildAt(i)
                if (view is Button) {
                    val keyText = view.text.toString()
                    if (keyText != "C" && keyText != "OK") {
                        view.setOnClickListener {
                            if (pinBuffer.length < 4) {
                                pinBuffer += keyText
                                updatePINDisplay(pinBuffer.length)
                                if (pinBuffer.length == 4) {
                                    // Auto check passcode
                                    if (pinBuffer == activePin) {
                                        binding.layoutAppLockOverlay.visibility = View.GONE
                                        Toast.makeText(this, "Success: Safe Box Unlocked!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this, "Wrong PIN Code! Try again.", Toast.LENGTH_SHORT).show()
                                        pinBuffer = ""
                                        updatePINDisplay(0)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            binding.btnPinClear.setOnClickListener {
                pinBuffer = ""
                updatePINDisplay(0)
            }

            binding.btnPinOk.setOnClickListener {
                if (pinBuffer == activePin) {
                    binding.layoutAppLockOverlay.visibility = View.GONE
                } else {
                    Toast.makeText(this, "Wrong PIN Code!", Toast.LENGTH_SHORT).show()
                    pinBuffer = ""
                    updatePINDisplay(0)
                }
            }
        } else {
            binding.layoutAppLockOverlay.visibility = View.GONE
        }
    }

    private fun updatePINDisplay(length: Int) {
        val builder = java.lang.StringBuilder()
        for (i in 0..3) {
            if (i < length) {
                builder.append("⬤  ")
            } else {
                builder.append("◯  ")
            }
        }
        binding.txtPINDisplay.text = builder.toString().trim()
    }

    // ==============================================
    // CORE LIST & SEARCH OBSERVATION
    // ==============================================
    private fun setupNotesListView() {
        binding.rvNotes.layoutManager = LinearLayoutManager(this)

        adapter = NotesListAdapter(
            onItemClick = { note ->
                // Check Lock note first
                if (note.isLocked && viewModel.prefs.appLockPin != null) {
                    promptNoteDecryptPin(note) {
                        openNoteForEditing(note)
                    }
                } else {
                    openNoteForEditing(note)
                }
            },
            onItemLongClick = { note ->
                showQuickNoteActionsPopup(note)
            }
        )
        binding.rvNotes.adapter = adapter

        // Standard FAB
        binding.fabAddNote.setOnClickListener {
            openNewNoteEditor()
        }

        // Drawer trigger
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        observeNotesCollection()
    }

    private fun observeNotesCollection() {
        lifecycleScope.launch {
            viewModel.filteredNotes.collectLatest { notes ->
                // Apply visual filter scoped to current category folder
                val displayedNotes = when (currentSection) {
                    "archive" -> notes.filter { it.isArchived && !it.isDeleted }
                    "bin" -> viewModel.recycleBinNotes.value
                    else -> notes.filter { !it.isArchived && !it.isDeleted }
                }

                adapter.submitList(displayedNotes)

                if (displayedNotes.isEmpty()) {
                    binding.layoutEmptyState.visibility = View.VISIBLE
                    binding.rvNotes.visibility = View.GONE
                } else {
                    binding.layoutEmptyState.visibility = View.GONE
                    binding.rvNotes.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun promptNoteDecryptPin(note: Note, onSuccess: () -> Unit) {
        val pinDialog = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pin, null)
        val etPin = view.findViewById<EditText>(R.id.etDialogPin)
        val btnValidate = view.findViewById<Button>(R.id.btnDialogPinSubmit)
        pinDialog.setView(view)
        val alert = pinDialog.create()

        btnValidate.setOnClickListener {
            val code = etPin.text.toString()
            if (code == viewModel.prefs.appLockPin) {
                alert.dismiss()
                onSuccess()
            } else {
                Toast.makeText(this, "Security Authentication Failed!", Toast.LENGTH_SHORT).show()
            }
        }
        alert.show()
    }

    private fun showQuickNoteActionsPopup(note: Note) {
        val popup = PopupMenu(this, binding.btnSort)
        popup.menu.add(0, 1, 0, if (note.isPinned) "📍 Unpin Note" else "📍 Pin Note")
        popup.menu.add(0, 2, 0, if (note.isFavorite) "⭐ Remove Favorite" else "⭐ Add Favorite")
        popup.menu.add(0, 3, 0, if (note.isArchived) "📥 Unarchive" else "📥 Archive Note")
        popup.menu.add(0, 4, 1, "📋 Copy Note content")
        popup.menu.add(0, 5, 2, "👯 Duplicate Note")
        popup.menu.add(0, 6, 3, "📤 Quick Share Note")
        popup.menu.add(0, 7, 4, "❌ Move to Recycle Bin")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> viewModel.togglePin(note)
                2 -> viewModel.toggleFavorite(note)
                3 -> viewModel.toggleArchive(note)
                4 -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("note", note.content)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Copied note text to Clipboard!", Toast.LENGTH_SHORT).show()
                }
                5 -> viewModel.duplicateNote(note)
                6 -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, note.title)
                        putExtra(Intent.EXTRA_TEXT, "${note.title}\n\n${note.content}")
                    }
                    startActivity(Intent.createChooser(intent, "Share via"))
                }
                7 -> viewModel.moveToRecycleBin(note)
            }
            true
        }
        popup.show()
    }

    // ==============================================
    // DRAWER NAVIGATION
    // ==============================================
    private fun setupDrawerNavigation() {
        binding.navActiveNotes.setOnClickListener {
            currentSection = "active"
            binding.txtCurrentFolderTitle.text = "All Active Notes"
            binding.fabAddNote.visibility = View.VISIBLE
            observeNotesCollection()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.navArchive.setOnClickListener {
            currentSection = "archive"
            binding.txtCurrentFolderTitle.text = "Archived Folder Box"
            binding.fabAddNote.visibility = View.GONE
            observeNotesCollection()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.navRecycleBin.setOnClickListener {
            currentSection = "bin"
            binding.txtCurrentFolderTitle.text = "Recycle Bin Vault"
            binding.fabAddNote.visibility = View.GONE
            observeNotesCollection()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            
            // Allow user to empty recycle bin easily on long presses
            Snackbar.make(binding.root, "Welcome to Recycle Bin. Clean permanently?", Snackbar.LENGTH_LONG)
                .setAction("Empty Bin") {
                    viewModel.emptyRecycleBin()
                }.show()
        }

        binding.navSettings.setOnClickListener {
            binding.layoutSettingsOverlay.visibility = View.VISIBLE
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.navPremium.setOnClickListener {
            binding.layoutSettingsOverlay.visibility = View.VISIBLE
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(this, "NoteNova Plus simulation billing activated below!", Toast.LENGTH_LONG).show()
        }
    }

    // ==============================================
    // FILTERS, SEARCH & SORTS
    // ==============================================
    private fun setupCategoryPickers() {
        val clickMap = mapOf(
            binding.chipAll to "All",
            binding.chipGeneral to "General",
            binding.chipWork to "Work",
            binding.chipPersonal to "Personal",
            binding.chipChecklists to "Checklists"
        )

        for ((view, category) in clickMap) {
            view.setOnClickListener {
                // Clear active selected background styles
                clickMap.keys.forEach { it.background = ContextCompat.getDrawable(this, R.drawable.tag_bg_unselected) }
                clickMap.keys.forEach { it.setTextColor(ContextCompat.getColor(this, android.R.color.black)) }

                view.background = ContextCompat.getDrawable(this, R.drawable.tag_bg_selected)
                view.setTextColor(ContextCompat.getColor(this, android.R.color.white))

                if (category == "Checklists") {
                    viewModel.setSelectedCategory("All")
                    viewModel.setSearchQuery("")
                    // We can also filter lists manually by isChecklist flag
                    lifecycleScope.launch {
                        viewModel.allActiveNotes.collectLatest { notes ->
                            adapter.submitList(notes.filter { it.isChecklist })
                        }
                    }
                } else {
                    viewModel.setSelectedCategory(category)
                }
            }
        }
    }

    private fun setupSortSelector() {
        binding.btnSort.setOnClickListener {
            val popup = PopupMenu(this, binding.btnSort)
            popup.menu.add(0, 1, 0, "📅 Sort: Last Modified (Newest)")
            popup.menu.add(0, 2, 0, "📅 Sort: Last Modified (Oldest)")
            popup.menu.add(0, 3, 0, "🔤 Sort: Alphabetically A-Z")
            popup.menu.add(0, 4, 0, "🔤 Sort: Alphabetically Z-A")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> viewModel.setSortOrder("modified_desc")
                    2 -> viewModel.setSortOrder("modified_asc")
                    3 -> viewModel.setSortOrder("title_asc")
                    4 -> viewModel.setSortOrder("title_desc")
                }
                observeNotesCollection()
                true
            }
            popup.show()
        }
    }

    private fun setupSearchInput() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ==============================================
    // EDITOR WORKSPACE & AUTO SAVE
    // ==============================================
    private fun setupEditorWorkspace() {
        // Setup Category spinner items
        val categories = arrayOf("General", "Work", "Personal", "Ideas", "Reminders")
        val adapterSpinner = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapterSpinner

        binding.btnEditorBack.setOnClickListener {
            closeEditorAndSave()
        }

        binding.btnToggleChecklist.setOnClickListener {
            isChecklistMode = !isChecklistMode
            updateEditorChecklistWorkspace()
        }

        // Add Checklist Task row
        binding.btnAddChecklistItem.setOnClickListener {
            addChecklistEditRow("", false)
        }

        // Set Reminders Date/Time pickers
        binding.btnSetReminder.setOnClickListener {
            setupDateTimePicker()
        }

        binding.btnClearReminder.setOnClickListener {
            selectedReminderTimestamp = 0L
            binding.layoutActiveReminderTag.visibility = View.GONE
        }

        binding.btnClearAttachment.setOnClickListener {
            selectedAttachmentPath = null
            selectedAttachmentType = null
            binding.layoutAttachmentTag.visibility = View.GONE
        }

        // Attach buttons
        binding.btnAttachImage.setOnClickListener {
            selectedAttachmentPath = "image_${System.currentTimeMillis()}.png"
            selectedAttachmentType = "image"
            binding.txtAttachmentDetails.text = "Attachment added: ${selectedAttachmentPath}"
            binding.layoutAttachmentTag.visibility = View.VISIBLE
            Toast.makeText(this, "Simulated Camera Sketch attached!", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttachAudio.setOnClickListener {
            selectedAttachmentPath = "recording_${System.currentTimeMillis()}.mp3"
            selectedAttachmentType = "audio"
            binding.txtAttachmentDetails.text = "Attachment added: ${selectedAttachmentPath}"
            binding.layoutAttachmentTag.visibility = View.VISIBLE
            Toast.makeText(this, "Simulated Voice Dictation attached!", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttachPdf.setOnClickListener {
            selectedAttachmentPath = "document_${System.currentTimeMillis()}.pdf"
            selectedAttachmentType = "pdf"
            binding.txtAttachmentDetails.text = "Attachment added: ${selectedAttachmentPath}"
            binding.layoutAttachmentTag.visibility = View.VISIBLE
            Toast.makeText(this, "Simulated Professional PDF document attached!", Toast.LENGTH_SHORT).show()
        }

        // Undo & Redo Bindings
        binding.btnUndo.setOnClickListener {
            val state = viewModel.performUndo()
            if (state != null) {
                binding.etNoteTitle.setText(state.title)
                binding.etNoteContent.setText(state.content)
            } else {
                Toast.makeText(this, "Nothing left to Undo!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRedo.setOnClickListener {
            val state = viewModel.performRedo()
            if (state != null) {
                binding.etNoteTitle.setText(state.title)
                binding.etNoteContent.setText(state.content)
            } else {
                Toast.makeText(this, "Nothing left to Redo!", Toast.LENGTH_SHORT).show()
            }
        }

        // Monitor typing triggers for Undo-Redo
        binding.etNoteContent.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculateWordCounts(s?.toString() ?: "")
                viewModel.pushEditorState(binding.etNoteTitle.text.toString(), s?.toString() ?: "")
            }
        })

        // Pin inside editor
        binding.btnEditorPin.setOnClickListener {
            lifecycleScope.launch {
                val dbNote = viewModel.allActiveNotes.value.find { it.id == currentEditingNoteId }
                if (dbNote != null) {
                    viewModel.togglePin(dbNote)
                    binding.btnEditorPin.setImageResource(
                        if (!dbNote.isPinned) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
                    )
                }
            }
        }

        binding.btnEditorDuplicate.setOnClickListener {
            lifecycleScope.launch {
                val dbNote = viewModel.allActiveNotes.value.find { it.id == currentEditingNoteId }
                if (dbNote != null) {
                    viewModel.duplicateNote(dbNote)
                    Toast.makeText(this@MainActivity, "Duplicate Note created successfully!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnEditorDelete.setOnClickListener {
            lifecycleScope.launch {
                val dbNote = viewModel.allActiveNotes.value.find { it.id == currentEditingNoteId }
                if (dbNote != null) {
                    viewModel.moveToRecycleBin(dbNote)
                    binding.layoutNoteEditorOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Moved Note to recycle bin.", View.VISIBLE).show()
                }
            }
        }
    }

    private fun calculateWordCounts(text: String) {
        val words = if (text.trim().isEmpty()) 0 else text.trim().split("\\s+".toRegex()).size
        val characters = text.length
        val readingTime = (words / 200).coerceAtLeast(1)

        binding.txtWordsCount.text = "$words words"
        binding.txtCharsCount.text = "$characters characters"
        binding.txtReadingTime.text = "$readingTime min read"
    }

    private fun openNewNoteEditor() {
        currentEditingNoteId = 0L
        isChecklistMode = false
        selectedReminderTimestamp = 0L
        selectedAttachmentPath = null
        selectedAttachmentType = null

        binding.etNoteTitle.setText("")
        binding.etNoteTags.setText("")
        binding.etNoteContent.setText("")
        binding.layoutActiveReminderTag.visibility = View.GONE
        binding.layoutAttachmentTag.visibility = View.GONE
        binding.layoutChecklistHolder.visibility = View.GONE
        binding.etNoteContent.visibility = View.VISIBLE

        binding.spinnerCategory.setSelection(0)
        binding.cbLockNote.isChecked = false

        viewModel.clearEditorCache()
        viewModel.pushEditorState("", "")

        binding.layoutNoteEditorOverlay.visibility = View.VISIBLE
    }

    private fun openNoteForEditing(note: Note) {
        currentEditingNoteId = note.id
        isChecklistMode = note.isChecklist
        selectedReminderTimestamp = note.reminderTime
        selectedAttachmentPath = note.attachmentPath
        selectedAttachmentType = note.attachmentType

        binding.etNoteTitle.setText(note.title)
        binding.etNoteTags.setText(note.tags)
        binding.etNoteContent.setText(note.content)

        // Set Category selection index
        val categories = arrayOf("General", "Work", "Personal", "Ideas", "Reminders")
        val index = categories.indexOf(note.category).coerceAtLeast(0)
        binding.spinnerCategory.setSelection(index)

        binding.cbLockNote.isChecked = note.isLocked

        // Apply Reminder Details
        if (note.reminderTime > System.currentTimeMillis() && !note.isReminderDismissed) {
            val date = java.text.DateFormat.getDateTimeInstance().format(Date(note.reminderTime))
            binding.txtReminderDetails.text = "Alarm schedule: $date"
            binding.layoutActiveReminderTag.visibility = View.VISIBLE
        } else {
            binding.layoutActiveReminderTag.visibility = View.GONE
        }

        // Apply Attachment Details
        if (note.attachmentPath != null) {
            binding.txtAttachmentDetails.text = "Attachment added: ${note.attachmentPath}"
            binding.layoutAttachmentTag.visibility = View.VISIBLE
        } else {
            binding.layoutAttachmentTag.visibility = View.GONE
        }

        updateEditorChecklistWorkspace()

        if (note.isChecklist) {
            binding.listChecklistElements.removeAllViews()
            try {
                val array = JSONArray(note.checklistJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    addChecklistEditRow(obj.getString("text"), obj.getBoolean("checked"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        binding.btnEditorPin.setImageResource(
            if (note.isPinned) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )

        viewModel.clearEditorCache()
        viewModel.pushEditorState(note.title, note.content)
        calculateWordCounts(note.content)

        binding.layoutNoteEditorOverlay.visibility = View.VISIBLE
    }

    private fun updateEditorChecklistWorkspace() {
        if (isChecklistMode) {
            binding.etNoteContent.visibility = View.GONE
            binding.layoutChecklistHolder.visibility = View.VISIBLE
            binding.btnToggleChecklist.text = "Switch back to standard plain text workspace"
            if (binding.listChecklistElements.childCount == 0) {
                addChecklistEditRow("", false)
            }
        } else {
            binding.etNoteContent.visibility = View.VISIBLE
            binding.layoutChecklistHolder.visibility = View.GONE
            binding.btnToggleChecklist.text = "Interactive Task Checklist workspace"
        }
    }

    private fun addChecklistEditRow(text: String, isChecked: Boolean) {
        val rowInflate = ItemChecklistBinding.inflate(LayoutInflater.from(this), binding.listChecklistElements, false)
        rowInflate.etItemText.setText(text)
        rowInflate.cbItemCheck.isChecked = isChecked

        // Line-through when checked
        if (isChecked) {
            rowInflate.etItemText.paintFlags = rowInflate.etItemText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }

        rowInflate.cbItemCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                rowInflate.etItemText.paintFlags = rowInflate.etItemText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                rowInflate.etItemText.paintFlags = rowInflate.etItemText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }

        rowInflate.btnRemoveItem.setOnClickListener {
            binding.listChecklistElements.removeView(rowInflate.root)
        }

        binding.listChecklistElements.addView(rowInflate.root)
    }

    private fun compileChecklistToJson(): Pair<String, String> {
        val array = JSONArray()
        val plainTextBuilder = java.lang.StringBuilder()
        val count = binding.listChecklistElements.childCount
        for (i in 0 until count) {
            val child = binding.listChecklistElements.getChildAt(i)
            val cb = child.findViewById<CheckBox>(R.id.cbItemCheck)
            val et = child.findViewById<EditText>(R.id.etItemText)
            if (et != null && cb != null) {
                val str = et.text.toString()
                val isChecked = cb.isChecked
                if (str.trim().isNotEmpty()) {
                    val obj = JSONObject()
                    obj.put("text", str)
                    obj.put("checked", isChecked)
                    array.put(obj)

                    val box = if (isChecked) "[x]" else "[ ]"
                    plainTextBuilder.append("$box $str\n")
                }
            }
        }
        return Pair(array.toString(), plainTextBuilder.toString().trim())
    }

    private fun setupDateTimePicker() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, day)

                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)

                        selectedReminderTimestamp = calendar.timeInMillis
                        if (selectedReminderTimestamp < System.currentTimeMillis()) {
                            Toast.makeText(this, "Alert schedule cannot be in past!", Toast.LENGTH_SHORT).show()
                            selectedReminderTimestamp = 0
                            return@TimePickerDialog
                        }

                        val dateFormatted = java.text.DateFormat.getDateTimeInstance().format(Date(selectedReminderTimestamp))
                        binding.txtReminderDetails.text = "Alarm schedule: $dateFormatted"
                        binding.layoutActiveReminderTag.visibility = View.VISIBLE
                        Toast.makeText(this, "High priority custom melody alarm set!", Toast.LENGTH_SHORT).show()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun closeEditorAndSave() {
        val title = binding.etNoteTitle.text.toString().trim()
        val tags = binding.etNoteTags.text.toString().trim()
        val spinnerCategory = binding.spinnerCategory.selectedItem?.toString() ?: "General"
        val isLocked = binding.cbLockNote.isChecked

        val finalContent: String
        val finalCheckJson: String
        if (isChecklistMode) {
            val checklistData = compileChecklistToJson()
            finalCheckJson = checklistData.first
            finalContent = checklistData.second
        } else {
            finalContent = binding.etNoteContent.text.toString()
            finalCheckJson = "[]"
        }

        // Only save if there was some text input
        if (title.isNotEmpty() || finalContent.isNotEmpty()) {
            viewModel.saveNote(
                id = currentEditingNoteId,
                title = title,
                content = finalContent,
                isChecklist = isChecklistMode,
                checklistJson = finalCheckJson,
                category = spinnerCategory,
                tags = tags,
                reminderTime = selectedReminderTimestamp,
                reminderRepeat = "none",
                isHighPriority = true,
                attachmentPath = selectedAttachmentPath,
                attachmentType = selectedAttachmentType,
                isLocked = isLocked,
                passwordHash = if (isLocked) viewModel.prefs.appLockPin else null
            ) {
                Toast.makeText(this, "Success: Note Auto-Saved!", Toast.LENGTH_SHORT).show()
            }
        }
        binding.layoutNoteEditorOverlay.visibility = View.GONE
    }

    // ==============================================
    // SETTINGS, PREFS, MONETIZATION & BACKUPS
    // ==============================================
    private fun setupSettingsAndPremium() {
        // Init view parameters based on prefs
        binding.switchLockPin.isChecked = viewModel.prefs.appLockPin != null

        val themeSelId = when (viewModel.prefs.themeMode) {
            "light" -> R.id.rbThemeLight
            "dark" -> R.id.rbThemeDark
            else -> R.id.rbThemeSystem
        }
        binding.rgThemeSelect.check(themeSelId)

        binding.rgThemeSelect.setOnCheckedChangeListener { _, checkedId ->
            val modeStr = when (checkedId) {
                R.id.rbThemeLight -> "light"
                R.id.rbThemeDark -> "dark"
                else -> "system"
            }
            viewModel.prefs.themeMode = modeStr
            applyThemePref(modeStr)
        }

        binding.switchLockPin.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Open dialog setup to type 4 PIN digits
                promptCreateLockPin()
            } else {
                viewModel.prefs.appLockPin = null
                Toast.makeText(this, "Safe Box PIN lock deactivated.", Toast.LENGTH_SHORT).show()
            }
        }

        // Trigger Backups
        binding.btnTriggerBackup.setOnClickListener {
            viewModel.backupDatabaseLocal { success, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnTriggerRestore.setOnClickListener {
            viewModel.restoreDatabaseLocal { success, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (success) {
                    // Reset App UI data
                    observeNotesCollection()
                }
            }
        }

        // Premium Monetization Simulations
        checkPremiumCrownWidget()

        binding.btnSimulateBilling.setOnClickListener {
            if (viewModel.prefs.isPremium) {
                // Turn off
                viewModel.prefs.isPremium = false
                Toast.makeText(this, "Simulated Billing Status: Reset to Basic Version.", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.prefs.isPremium = true
                Toast.makeText(this, "🌟 Congratulations! NoteNova Plus Lifetime unlocked successfully!", Toast.LENGTH_LONG).show()
            }
            checkPremiumCrownWidget()
        }

        // Mock dismissing ads button
        binding.btnUnlockPremiumAd.setOnClickListener {
            viewModel.prefs.isPremium = true
            checkPremiumCrownWidget()
            Toast.makeText(this, "Premium Plus Activated: Ads removed completely!", Toast.LENGTH_SHORT).show()
        }

        // Settings Back toolbar button
        binding.settingsToolbar.setNavigationOnClickListener {
            binding.layoutSettingsOverlay.visibility = View.GONE
        }
    }

    private fun checkPremiumCrownWidget() {
        if (viewModel.prefs.isPremium) {
            binding.txtPremiumStatus.text = "Subscription Status: ACTIVE LIFETIME PRO"
            binding.btnSimulateBilling.text = "Deactivate NoteNova Plus Premium"
            binding.btnSimulateBilling.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.btnPremiumCrown.visibility = View.VISIBLE
            binding.layoutSimulatedAd.visibility = View.GONE
        } else {
            binding.txtPremiumStatus.text = "Subscription Status: BASIC EDITION (Contains ads)"
            binding.btnSimulateBilling.text = "Unlock Professional Lifetime ($4.99)"
            binding.btnSimulateBilling.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            binding.btnPremiumCrown.visibility = View.GONE
            binding.layoutSimulatedAd.visibility = View.VISIBLE
        }
    }

    private fun promptCreateLockPin() {
        val pinDialog = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pin_setup, null)
        val etPin = view.findViewById<EditText>(R.id.etSetupPin)
        val btnSave = view.findViewById<Button>(R.id.btnSetupPinSubmit)
        pinDialog.setView(view)
        val alert = pinDialog.create()

        btnSave.setOnClickListener {
            val code = etPin.text.toString()
            if (code.length == 4) {
                viewModel.prefs.appLockPin = code
                Toast.makeText(this, "Safe box PIN lock set to $code!", Toast.LENGTH_SHORT).show()
                alert.dismiss()
            } else {
                Toast.makeText(this, "PIN must be exactly 4 digits!", Toast.LENGTH_SHORT).show()
            }
        }
        alert.setOnCancelListener {
            binding.switchLockPin.isChecked = false
        }
        alert.show()
    }
}
