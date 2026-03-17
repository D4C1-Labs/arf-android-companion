package com.flipper.psadecrypt.subghz

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipper.psadecrypt.MainActivity
import com.flipper.psadecrypt.R
import com.flipper.psadecrypt.storage.FlipperStorageApi
import kotlinx.coroutines.*
import java.io.File

class SubGhzSettingsFragment : Fragment() {

    companion object {
        private const val SETTINGS_PATH = "/ext/subghz/assets/setting_user"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var notConnectedText: TextView
    private lateinit var topBar: View

    private lateinit var adapter: SubGhzSettingsAdapter
    private var settings = SubGhzSettings()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val storageApi: FlipperStorageApi?
        get() = (activity as? MainActivity)?.storageApi

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_subghz_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_settings)
        loadingProgress = view.findViewById(R.id.progress_loading)
        errorText = view.findViewById(R.id.txt_error)
        notConnectedText = view.findViewById(R.id.txt_not_connected)
        topBar = view.findViewById(R.id.top_bar)

        adapter = SubGhzSettingsAdapter(
            onToggleChanged = { checked ->
                settings.addStandardFrequencies = checked
                refreshList()
            },
            onDeleteFrequency = { hz ->
                settings.frequencies.remove(hz)
                if (settings.defaultFrequency == hz) settings.defaultFrequency = null
                refreshList()
            },
            onDeleteHopperFrequency = { hz ->
                settings.hopperFrequencies.remove(hz)
                refreshList()
            },
            onDeletePreset = { index ->
                confirmDeletePreset(index)
            },
            onEditPreset = { index ->
                showEditPresetDialog(index)
            },
            onAddClicked = { type ->
                when (type) {
                    SettingsItem.AddType.FREQUENCY -> showAddFrequencyDialog(isHopper = false)
                    SettingsItem.AddType.HOPPER -> showAddFrequencyDialog(isHopper = true)
                    SettingsItem.AddType.PRESET -> showAddPresetDialog()
                    SettingsItem.AddType.HOPPING_PRESET -> showAddHoppingPresetDialog()
                }
            },
            onSetDefaultFrequency = { hz ->
                settings.defaultFrequency = hz
                refreshList()
            },
            onDeleteHoppingPreset = { index ->
                settings.hoppingPresets.removeAt(index)
                refreshList()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btn_refresh).setOnClickListener { loadSettings() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener { saveSettings() }

        if (storageApi != null) {
            loadSettings()
        } else {
            showNotConnected()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.coroutineContext.cancelChildren()
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!isAdded) return
        if (connected) {
            notConnectedText.visibility = View.GONE
            topBar.visibility = View.VISIBLE
            recyclerView.visibility = View.VISIBLE
            loadSettings()
        } else {
            showNotConnected()
        }
    }

    private fun showNotConnected() {
        notConnectedText.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        loadingProgress.visibility = View.GONE
        errorText.visibility = View.GONE
    }

    // --- Load / Save ---

    private fun loadSettings() {
        val api = storageApi
        if (api == null) {
            showNotConnected()
            return
        }

        notConnectedText.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        recyclerView.visibility = View.VISIBLE
        loadingProgress.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        scope.launch {
            val tempFile = File(requireContext().cacheDir, "setting_user_tmp")
            val result = withContext(Dispatchers.IO) { api.download(SETTINGS_PATH, tempFile) }

            if (!isAdded) {
                tempFile.delete()
                return@launch
            }

            loadingProgress.visibility = View.GONE

            result.onSuccess {
                val content = withContext(Dispatchers.IO) { tempFile.readText() }
                tempFile.delete()
                settings = SubGhzSettingsParser.parse(content)
                refreshList()
                log("Settings loaded")
            }.onFailure { e ->
                tempFile.delete()
                val msg = e.message ?: "Unknown error"
                if (msg.contains("ERROR_STORAGE_NOT_EXIST", ignoreCase = true) ||
                    msg.contains("not exist", ignoreCase = true)
                ) {
                    // File doesn't exist — start with defaults
                    settings = SubGhzSettings()
                    refreshList()
                    log("No setting_user file found, starting with defaults")
                    Toast.makeText(requireContext(), "No settings file found — starting fresh", Toast.LENGTH_SHORT).show()
                } else {
                    errorText.text = "Error: $msg"
                    errorText.visibility = View.VISIBLE
                    log("Load failed: $msg")
                }
            }
        }
    }

    private fun saveSettings() {
        val api = storageApi
        if (api == null) {
            Toast.makeText(requireContext(), "Not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val content = SubGhzSettingsParser.serialize(settings)

        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Saving")
            .setMessage("Uploading settings to Flipper...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val tempFile = File(requireContext().cacheDir, "setting_user_upload")
            withContext(Dispatchers.IO) { tempFile.writeText(content) }

            // Ensure the directory exists
            withContext(Dispatchers.IO) { api.mkdir("/ext/subghz/assets") }

            val result = withContext(Dispatchers.IO) { api.upload(tempFile, SETTINGS_PATH) }
            progressDialog.dismiss()
            tempFile.delete()

            if (!isAdded) return@launch

            result.onSuccess {
                Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
                log("Settings saved to $SETTINGS_PATH")
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                log("Save failed: ${e.message}")
            }
        }
    }

    private fun refreshList() {
        adapter.updateItems(settings)
    }

    // --- Dialogs ---

    private fun showAddFrequencyDialog(isHopper: Boolean) {
        val input = EditText(requireContext()).apply {
            hint = "Frequency in Hz (e.g. 433920000)"
            setPadding(48, 24, 48, 24)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (isHopper) "Add Hopper Frequency" else "Add Frequency")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val hz = input.text.toString().trim().toLongOrNull()
                if (hz == null || hz <= 0) {
                    Toast.makeText(requireContext(), "Invalid frequency", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (isHopper) {
                    settings.hopperFrequencies.add(hz)
                } else {
                    settings.frequencies.add(hz)
                }
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddPresetDialog() {
        showPresetDialog("Add Custom Preset", CustomPreset("", "CC1101", "")) { preset ->
            settings.customPresets.add(preset)
            refreshList()
        }
    }

    private fun showEditPresetDialog(index: Int) {
        val existing = settings.customPresets.getOrNull(index) ?: return
        showPresetDialog("Edit Custom Preset", existing.copy()) { edited ->
            settings.customPresets[index] = edited
            refreshList()
        }
    }

    private fun showPresetDialog(title: String, preset: CustomPreset, onDone: (CustomPreset) -> Unit) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_preset_editor, null)

        val nameInput = dialogView.findViewById<EditText>(R.id.edit_preset_name)
        val registerContainer = dialogView.findViewById<LinearLayout>(R.id.register_container)
        val addRegisterBtn = dialogView.findViewById<View>(R.id.btn_add_register)

        val paFields = listOf(
            dialogView.findViewById<EditText>(R.id.pa_0),
            dialogView.findViewById<EditText>(R.id.pa_1),
            dialogView.findViewById<EditText>(R.id.pa_2),
            dialogView.findViewById<EditText>(R.id.pa_3),
            dialogView.findViewById<EditText>(R.id.pa_4),
            dialogView.findViewById<EditText>(R.id.pa_5),
            dialogView.findViewById<EditText>(R.id.pa_6),
            dialogView.findViewById<EditText>(R.id.pa_7)
        )

        nameInput.setText(preset.name)

        // Parse existing data into register pairs + PA table
        val (registers, paTable) = if (preset.data.isNotBlank()) {
            SubGhzSettingsParser.parsePresetData(preset.data)
        } else {
            emptyList<Pair<String, String>>() to List(8) { "00" }
        }

        // Populate PA table fields
        for (i in paFields.indices) {
            paFields[i].setText(if (i < paTable.size) paTable[i] else "00")
        }

        // Populate register rows
        for ((addr, value) in registers) {
            addRegisterRow(registerContainer, addr, value)
        }

        // Add Register button
        addRegisterBtn.setOnClickListener {
            addRegisterRow(registerContainer, "", "")
        }

        // Paste hex data from clipboard
        dialogView.findViewById<View>(R.id.btn_paste_hex).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clipText.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate it looks like hex bytes
            val hexPattern = Regex("^([0-9A-Fa-f]{2}\\s)*[0-9A-Fa-f]{2}$")
            if (!hexPattern.matches(clipText)) {
                Toast.makeText(requireContext(), "Clipboard doesn't contain valid hex data", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val (pastedRegisters, pastedPa) = SubGhzSettingsParser.parsePresetData(clipText)

            // Clear existing rows and repopulate
            registerContainer.removeAllViews()
            for ((addr, value) in pastedRegisters) {
                addRegisterRow(registerContainer, addr, value)
            }

            // Update PA table fields
            for (i in paFields.indices) {
                paFields[i].setText(if (i < pastedPa.size) pastedPa[i] else "00")
            }

            Toast.makeText(requireContext(), "Pasted ${pastedRegisters.size} registers", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Collect register pairs
                val regPairs = mutableListOf<Pair<String, String>>()
                val hexPattern = Regex("^[0-9A-Fa-f]{2}$")
                for (i in 0 until registerContainer.childCount) {
                    val row = registerContainer.getChildAt(i)
                    val addr = row.findViewById<EditText>(R.id.edit_addr).text.toString().trim().uppercase()
                    val value = row.findViewById<EditText>(R.id.edit_val).text.toString().trim().uppercase()
                    if (addr.isEmpty() && value.isEmpty()) continue // skip empty rows
                    if (!hexPattern.matches(addr) || !hexPattern.matches(value)) {
                        Toast.makeText(requireContext(), "Invalid register: 0x$addr = 0x$value", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    regPairs.add(addr to value)
                }

                // Collect PA table
                val pa = mutableListOf<String>()
                for (field in paFields) {
                    val byte = field.text.toString().trim().uppercase().ifEmpty { "00" }
                    if (!hexPattern.matches(byte)) {
                        Toast.makeText(requireContext(), "Invalid PA table byte: $byte", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    pa.add(byte)
                }

                val data = SubGhzSettingsParser.serializePresetData(regPairs, pa)
                onDone(CustomPreset(name, "CC1101", data))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addRegisterRow(container: LinearLayout, addr: String, value: String) {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_register_pair, container, false)
        row.findViewById<EditText>(R.id.edit_addr).setText(addr)
        row.findViewById<EditText>(R.id.edit_val).setText(value)
        row.findViewById<ImageButton>(R.id.btn_delete_reg).setOnClickListener {
            container.removeView(row)
        }
        container.addView(row)
    }

    private fun showAddHoppingPresetDialog() {
        val builtInPresets = listOf("AM270", "AM650", "FM238", "FM476")
        val customNames = settings.customPresets.map { it.name }
        val allPresets = (builtInPresets + customNames).distinct().toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Add Hopping Preset")
            .setItems(allPresets) { _, which ->
                settings.hoppingPresets.add(allPresets[which])
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePreset(index: Int) {
        val preset = settings.customPresets.getOrNull(index) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Preset")
            .setMessage("Delete preset \"${preset.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                settings.customPresets.removeAt(index)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun log(msg: String) {
        (activity as? MainActivity)?.appendLog("[SubGhz] $msg")
    }
}
