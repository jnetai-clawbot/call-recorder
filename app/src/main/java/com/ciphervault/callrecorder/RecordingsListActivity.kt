package com.ciphervault.callrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
        RL999("RL999", "Unknown error")
    }

    private lateinit var binding: ActivityRecordingsBinding

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
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val recordingsDirs = listOf(
                File(dcimDir, "Recordings"),
                dcimDir,
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CallRecorder")
            )

            val formats = listOf("wav", "flac", "mp3", "aac", "ogg")
            val allFiles = mutableListOf<File>()

            for (dir in recordingsDirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in formats }
                        ?.let { allFiles.addAll(it) }
                }
            }

            val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

            val entries = allFiles
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
                binding.recyclerView.adapter = RecordingsAdapter(entries) { entry ->
                    openRecording(entry)
                }
            }

            logDebug("Loaded ${entries.size} recordings")
        } catch (e: Exception) {
            reportError(ErrorCode.RL001, "Failed to load recordings", e)
            binding.tvEmpty.text = "Error loading recordings"
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun openRecording(entry: RecordingEntry) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", entry.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open ${entry.name}"))
        } catch (e: Exception) {
            logDebug("Cannot open: ${e.message}")
            // Try sharing instead
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", entry.file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share ${entry.name}"))
            } catch (e2: Exception) {
                Toast.makeText(this, "Saved at: ${entry.file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
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
        private val onClick: (RecordingEntry) -> Unit
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
            holder.root.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = entries.size
    }
}
