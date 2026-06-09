package com.ciphervault.callrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ciphervault.callrecorder.databinding.ActivityRecordingsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsListActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CR_RecordingsList"
        const val ERROR_PREFIX = "CR-RL-"
    }

    enum class ErrorCode(val code: String, val description: String) {
        RL001("RL001", "Failed to list recordings"),
        RL002("RL002", "Failed to move recording"),
        RL003("RL003", "Failed to delete recording"),
        RL999("RL999", "Unknown error")
    }

    private lateinit var binding: ActivityRecordingsBinding
    private var entries: List<RecordingEntry> = emptyList()

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        Log.e(TAG, "[${ERROR_PREFIX}${code.code}] ${code.description}: $message", exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    data class RecordingEntry(
        val file: File,
        val name: String,
        val dateStr: String,
        val sizeStr: String,
        val format: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Recordings"

        loadRecordings()
    }

    private fun loadRecordings() {
        try {
            val recordingsDirs = mutableListOf<File>()
            getExternalFilesDir(null)?.let { recordingsDirs.add(File(it, "Recordings")) }
            recordingsDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CallRecorder"))
            recordingsDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            recordingsDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CallRecorder"))

            val formats = listOf("wav", "flac", "mp3", "aac", "ogg")
            val allFiles = mutableListOf<File>()

            for (dir in recordingsDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in formats }
                        ?.let { allFiles.addAll(it) }
                }
            }

            val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

            entries = allFiles
                .distinctBy { it.absolutePath }
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    RecordingEntry(
                        file = file,
                        name = file.name,
                        dateStr = dateFormat.format(Date(file.lastModified())),
                        sizeStr = formatSize(file.length()),
                        format = file.extension.uppercase()
                    )
                }

            if (entries.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
                binding.recyclerView.adapter = RecordingsAdapter(entries) { entry, view ->
                    showContextMenu(entry, view)
                }
            }

            logDebug("Loaded ${entries.size} recordings")
        } catch (e: Exception) {
            reportError(ErrorCode.RL001, "Failed to load recordings", e)
            binding.tvEmpty.text = "Error loading recordings"
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun showContextMenu(entry: RecordingEntry, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 2, 1, "Move to Storage Path")
        popup.menu.add(0, 3, 2, "Share")
        popup.menu.add(0, 4, 3, "Remove")

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> playRecording(entry)
                2 -> moveRecording(entry)
                3 -> shareRecording(entry)
                4 -> removeRecording(entry)
            }
            true
        }
        popup.show()
    }

    private fun playRecording(entry: RecordingEntry) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", entry.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play ${entry.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveRecording(entry: RecordingEntry) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val storagePath = prefs.getString(SettingsActivity.PREF_STORAGE_PATH, "DCIM") ?: "DCIM"
            val cleanPath = storagePath
                .removePrefix("/storage/emulated/0/")
                .removePrefix("storage/emulated/0/")
                .trim('/')
                .ifBlank { "DCIM" }
            val targetDir = File(Environment.getExternalStorageDirectory(), cleanPath)
            if (!targetDir.exists()) targetDir.mkdirs()

            val targetFile = File(targetDir, entry.file.name)
            entry.file.copyTo(targetFile, overwrite = true)

            Toast.makeText(this, "Moved to ${targetFile.absolutePath}", Toast.LENGTH_LONG).show()
            loadRecordings()
        } catch (e: Exception) {
            reportError(ErrorCode.RL002, "Move failed", e)
            Toast.makeText(this, "Move failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(entry: RecordingEntry) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", entry.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${entry.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeRecording(entry: RecordingEntry) {
        AlertDialog.Builder(this)
            .setTitle("Remove Recording")
            .setMessage("Delete ${entry.name}?\nThis cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                try {
                    if (entry.file.delete()) {
                        Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                        loadRecordings()
                    } else {
                        Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    reportError(ErrorCode.RL003, "Delete failed", e)
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class RecordingsAdapter(
        private val entries: List<RecordingEntry>,
        private val onLongClick: (RecordingEntry, View) -> Unit
    ) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvRecName)
            val tvDetails: TextView = view.findViewById(R.id.tvRecDetails)
            val tvFormat: TextView = view.findViewById(R.id.tvRecFormat)
            val root: View = view.findViewById(R.id.itemRoot)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.tvName.text = entry.name
            holder.tvDetails.text = "${entry.dateStr}  •  ${entry.sizeStr}"
            holder.tvFormat.text = entry.format
            holder.root.setOnLongClickListener {
                onLongClick(entry, it)
                true
            }
            holder.root.setOnClickListener {
                onLongClick(entry, it)
            }
        }

        override fun getItemCount(): Int = entries.size
    }
}
