package com.ciphervault.callrecorder

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ciphervault.callrecorder.databinding.ActivityContactPickerBinding

class ContactPickerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CR_ContactPicker"
        const val ERROR_PREFIX = "CR-CP-"
        const val EXTRA_MODE = "picker_mode"
        const val EXTRA_PREF_KEY = "pref_key"
        const val MODE_INCLUDE = "include"
        const val MODE_EXCLUDE = "exclude"

        const val PREF_INCLUDED_CONTACTS = "included_contacts"
        const val PREF_EXCLUDED_CONTACTS = "excluded_contacts"
    }

    enum class ErrorCode(val code: String, val description: String) {
        CP001("CP001", "Contacts permission denied"),
        CP002("CP002", "Failed to read contacts"),
        CP003("CP003", "Failed to save contact selection"),
        CP999("CP999", "Unknown contact picker error")
    }

    data class ContactInfo(val id: Long, val name: String, val phoneNumber: String)

    private lateinit var binding: ActivityContactPickerBinding
    private var contacts: List<ContactInfo> = emptyList()
    private var selectedIds: MutableSet<Long> = mutableSetOf()
    private var pickerMode: String = MODE_INCLUDE
    private var prefKey: String = PREF_INCLUDED_CONTACTS

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pickerMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_INCLUDE
        prefKey = intent.getStringExtra(EXTRA_PREF_KEY) ?: PREF_INCLUDED_CONTACTS
        supportActionBar?.title = if (pickerMode == MODE_INCLUDE) "Included Contacts" else "Excluded Contacts"

        loadSelectedContacts()
        loadContacts()

        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun loadSelectedContacts() {
        val prefs = getSharedPreferences("contact_selections", MODE_PRIVATE)
        val saved = prefs.getStringSet(prefKey, emptySet()) ?: emptySet()
        selectedIds = saved.mapNotNull { it.toLongOrNull() }.toMutableSet()
        logDebug("Loaded ${selectedIds.size} selected contacts for $prefKey")
    }

    private fun loadContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                200
            )
            return
        }
        readContactsFromDevice()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                readContactsFromDevice()
            } else {
                reportError(ErrorCode.CP001, "Contacts permission denied")
                binding.tvStatus.text = "Contacts permission required"
            }
        }
    }

    private fun readContactsFromDevice() {
        try {
            val contactList = mutableListOf<ContactInfo>()
            val contentResolver: ContentResolver = contentResolver

            val cursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val idCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol)
                    val number = it.getString(numCol)
                    if (name != null && number != null) {
                        contactList.add(ContactInfo(id, name, number))
                    }
                }
            }

            contacts = contactList.distinctBy { it.id }
            binding.tvStatus.text = "${contacts.size} contacts loaded"
            setupRecyclerView()
            logDebug("Loaded ${contacts.size} contacts from device")
        } catch (e: SecurityException) {
            reportError(ErrorCode.CP002, "Security exception reading contacts", e)
            binding.tvStatus.text = "Permission denied"
        } catch (e: Exception) {
            reportError(ErrorCode.CP002, "Failed to read contacts", e)
            binding.tvStatus.text = "Error loading contacts: ${e.message}"
        }
    }

    private fun setupRecyclerView() {
        val adapter = ContactAdapter(contacts, selectedIds) { contactId, checked ->
            if (checked) selectedIds.add(contactId) else selectedIds.remove(contactId)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun saveAndFinish() {
        try {
            val prefs = getSharedPreferences("contact_selections", MODE_PRIVATE)
            prefs.edit().putStringSet(prefKey, selectedIds.map { it.toString() }.toSet()).apply()
            logDebug("Saved ${selectedIds.size} contacts to $prefKey")
            setResult(RESULT_OK)
        } catch (e: Exception) {
            reportError(ErrorCode.CP003, "Failed to save selection", e)
        }
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class ContactAdapter(
        private val contacts: List<ContactInfo>,
        private val selectedIds: Set<Long>,
        private val onCheckedChange: (Long, Boolean) -> Unit
    ) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.cbContact)
            val tvName: TextView = view.findViewById(R.id.tvContactName)
            val tvNumber: TextView = view.findViewById(R.id.tvContactNumber)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = contacts[position]
            holder.tvName.text = contact.name
            holder.tvNumber.text = contact.phoneNumber
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = selectedIds.contains(contact.id)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                onCheckedChange(contact.id, isChecked)
            }
        }

        override fun getItemCount(): Int = contacts.size
    }
}
