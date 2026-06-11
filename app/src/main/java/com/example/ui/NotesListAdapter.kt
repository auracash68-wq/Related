package com.example.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemNoteBinding
import com.example.data.Note
import java.util.Date

class NotesListAdapter(
    private val onItemClick: (Note) -> Unit,
    private val onItemLongClick: (Note) -> Unit
) : ListAdapter<Note, NotesListAdapter.NoteViewHolder>(NoteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(private val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(note: Note) {
            binding.txtNoteTitle.text = note.title.ifEmpty { "Untitled Note" }
            binding.txtNoteText.text = if (note.isChecklist) {
                "[Task Checklist] " + note.content.take(60)
            } else {
                note.content.take(120)
            }
            binding.txtNoteCategory.text = note.category
            
            // Format Tags
            if (note.tags.trim().isNotEmpty()) {
                val formattedTags = note.tags.split(",")
                    .filter { it.trim().isNotEmpty() }
                    .joinToString(" ") { "#${it.trim()}" }
                binding.txtNoteTagsList.text = formattedTags
                binding.txtNoteTagsList.visibility = View.VISIBLE
            } else {
                binding.txtNoteTagsList.visibility = View.GONE
            }

            // Format date
            val date = Date(note.lastModified)
            binding.txtNoteDate.text = DateFormat.format("MMM d, yyyy", date).toString()

            // Visibility indicators
            binding.imgIndicatorPinned.visibility = if (note.isPinned) View.VISIBLE else View.GONE
            binding.imgIndicatorFavorite.visibility = if (note.isFavorite) View.VISIBLE else View.GONE
            binding.imgIndicatorLocked.visibility = if (note.isLocked) View.VISIBLE else View.GONE
            
            // If reminder is scheduled (greater than current unix time)
            if (note.reminderTime > System.currentTimeMillis() && !note.isReminderDismissed) {
                binding.imgIndicatorReminder.visibility = View.VISIBLE
            } else {
                binding.imgIndicatorReminder.visibility = View.GONE
            }

            // Click Handlers
            binding.root.setOnClickListener {
                onItemClick(note)
            }
            binding.root.setOnLongClickListener {
                onItemLongClick(note)
                true
            }
        }
    }

    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem == newItem
        }
    }
}
