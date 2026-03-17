package com.flipper.psadecrypt.subghz

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flipper.psadecrypt.R
import com.google.android.material.switchmaterial.SwitchMaterial

sealed class SettingsItem {
    data class SectionHeader(val title: String, val isCollapsed: Boolean, val childCount: Int) : SettingsItem()
    data class ToggleItem(val label: String, val checked: Boolean) : SettingsItem()
    data class FrequencyItem(val hz: Long, val isDefault: Boolean, val isHopper: Boolean) : SettingsItem()
    data class PresetItem(val index: Int, val preset: CustomPreset) : SettingsItem()
    data class HoppingPresetItem(val index: Int, val name: String) : SettingsItem()
    data class AddButton(val label: String, val type: AddType) : SettingsItem()

    enum class AddType { FREQUENCY, HOPPER, PRESET, HOPPING_PRESET }
}

class SubGhzSettingsAdapter(
    private val onToggleChanged: (Boolean) -> Unit,
    private val onDeleteFrequency: (Long) -> Unit,
    private val onDeleteHopperFrequency: (Long) -> Unit,
    private val onDeletePreset: (Int) -> Unit,
    private val onEditPreset: (Int) -> Unit,
    private val onAddClicked: (SettingsItem.AddType) -> Unit,
    private val onSetDefaultFrequency: (Long?) -> Unit,
    private val onDeleteHoppingPreset: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SettingsItem>()
    private val collapsedSections = mutableSetOf<String>()
    private var currentSettings: SubGhzSettings? = null

    companion object {
        private const val TYPE_SECTION_HEADER = 0
        private const val TYPE_TOGGLE = 1
        private const val TYPE_FREQUENCY = 2
        private const val TYPE_PRESET = 3
        private const val TYPE_ADD_BUTTON = 4
        private const val TYPE_HOPPING_PRESET = 5
    }

    fun updateItems(settings: SubGhzSettings) {
        currentSettings = settings
        rebuildItems()
    }

    private fun rebuildItems() {
        val settings = currentSettings ?: return
        items.clear()
        items.addAll(buildItems(settings))
        notifyDataSetChanged()
    }

    private fun toggleSection(title: String) {
        if (collapsedSections.contains(title)) {
            collapsedSections.remove(title)
        } else {
            collapsedSections.add(title)
        }
        rebuildItems()
    }

    private fun buildItems(settings: SubGhzSettings): List<SettingsItem> {
        val list = mutableListOf<SettingsItem>()

        // General section
        val generalCollapsed = "General" in collapsedSections
        list.add(SettingsItem.SectionHeader("General", generalCollapsed, 1))
        if (!generalCollapsed) {
            list.add(SettingsItem.ToggleItem("Add standard frequencies", settings.addStandardFrequencies))
        }

        // Frequencies section
        val freqCollapsed = "Frequencies" in collapsedSections
        list.add(SettingsItem.SectionHeader("Frequencies", freqCollapsed, settings.frequencies.size))
        if (!freqCollapsed) {
            for (freq in settings.frequencies) {
                list.add(SettingsItem.FrequencyItem(freq, freq == settings.defaultFrequency, isHopper = false))
            }
            list.add(SettingsItem.AddButton("+ Add Frequency", SettingsItem.AddType.FREQUENCY))
        }

        // Hopper frequencies section
        val hopperCollapsed = "Hopper Frequencies" in collapsedSections
        list.add(SettingsItem.SectionHeader("Hopper Frequencies", hopperCollapsed, settings.hopperFrequencies.size))
        if (!hopperCollapsed) {
            for (freq in settings.hopperFrequencies) {
                list.add(SettingsItem.FrequencyItem(freq, isDefault = false, isHopper = true))
            }
            list.add(SettingsItem.AddButton("+ Add Hopper Frequency", SettingsItem.AddType.HOPPER))
        }

        // Custom presets section
        val presetsCollapsed = "Custom Presets" in collapsedSections
        list.add(SettingsItem.SectionHeader("Custom Presets", presetsCollapsed, settings.customPresets.size))
        if (!presetsCollapsed) {
            for ((index, preset) in settings.customPresets.withIndex()) {
                list.add(SettingsItem.PresetItem(index, preset))
            }
            list.add(SettingsItem.AddButton("+ Add Preset", SettingsItem.AddType.PRESET))
        }

        // Hopping presets section
        val hoppingCollapsed = "Hopping Presets" in collapsedSections
        list.add(SettingsItem.SectionHeader("Hopping Presets", hoppingCollapsed, settings.hoppingPresets.size))
        if (!hoppingCollapsed) {
            for ((index, name) in settings.hoppingPresets.withIndex()) {
                list.add(SettingsItem.HoppingPresetItem(index, name))
            }
            list.add(SettingsItem.AddButton("+ Add Hopping Preset", SettingsItem.AddType.HOPPING_PRESET))
        }

        return list
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingsItem.SectionHeader -> TYPE_SECTION_HEADER
        is SettingsItem.ToggleItem -> TYPE_TOGGLE
        is SettingsItem.FrequencyItem -> TYPE_FREQUENCY
        is SettingsItem.PresetItem -> TYPE_PRESET
        is SettingsItem.HoppingPresetItem -> TYPE_HOPPING_PRESET
        is SettingsItem.AddButton -> TYPE_ADD_BUTTON
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION_HEADER -> SectionHeaderVH(inflater.inflate(R.layout.item_subghz_section_header, parent, false))
            TYPE_TOGGLE -> ToggleVH(inflater.inflate(R.layout.item_subghz_toggle, parent, false))
            TYPE_FREQUENCY -> FrequencyVH(inflater.inflate(R.layout.item_subghz_frequency, parent, false))
            TYPE_PRESET -> PresetVH(inflater.inflate(R.layout.item_subghz_preset, parent, false))
            TYPE_HOPPING_PRESET -> HoppingPresetVH(inflater.inflate(R.layout.item_subghz_preset, parent, false))
            TYPE_ADD_BUTTON -> AddButtonVH(inflater.inflate(R.layout.item_subghz_add_button, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingsItem.SectionHeader -> (holder as SectionHeaderVH).bind(item)
            is SettingsItem.ToggleItem -> (holder as ToggleVH).bind(item)
            is SettingsItem.FrequencyItem -> (holder as FrequencyVH).bind(item)
            is SettingsItem.PresetItem -> (holder as PresetVH).bind(item)
            is SettingsItem.HoppingPresetItem -> (holder as HoppingPresetVH).bind(item)
            is SettingsItem.AddButton -> (holder as AddButtonVH).bind(item)
        }
    }

    // --- ViewHolders ---

    inner class SectionHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val chevron: TextView = view.findViewById(R.id.txt_chevron)
        private val title: TextView = view.findViewById(R.id.txt_section_title)
        private val count: TextView = view.findViewById(R.id.txt_item_count)

        fun bind(item: SettingsItem.SectionHeader) {
            title.text = item.title
            chevron.text = if (item.isCollapsed) "\u25B6" else "\u25BC"
            if (item.isCollapsed && item.childCount > 0) {
                count.text = "${item.childCount}"
                count.visibility = View.VISIBLE
            } else {
                count.visibility = View.GONE
            }
            itemView.setOnClickListener { toggleSection(item.title) }
        }
    }

    inner class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.txt_toggle_label)
        private val switch: Switch = view.findViewById(R.id.switch_toggle)
        fun bind(item: SettingsItem.ToggleItem) {
            label.text = item.label
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = item.checked
            switch.setOnCheckedChangeListener { _, isChecked -> onToggleChanged(isChecked) }
        }
    }

    inner class FrequencyVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.txt_freq_label)
        private val value: TextView = view.findViewById(R.id.txt_freq_value)
        private val switch: SwitchMaterial = view.findViewById(R.id.switch_freq)
        fun bind(item: SettingsItem.FrequencyItem) {
            label.text = SubGhzSettingsParser.formatFrequency(item.hz)
            value.text = if (item.isHopper) "Hopper Frequency" else "Static Frequency"
            switch.isChecked = item.isDefault
            
            itemView.setOnLongClickListener {
                if (!item.isHopper) {
                    val popup = PopupMenu(it.context, it)
                    popup.menu.add(0, 1, 0, "Delete")
                    if (item.isDefault) {
                        popup.menu.add(0, 2, 0, "Unset as default")
                    } else {
                        popup.menu.add(0, 3, 0, "Set as default")
                    }
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            1 -> { onDeleteFrequency(item.hz); true }
                            2 -> { onSetDefaultFrequency(null); true }
                            3 -> { onSetDefaultFrequency(item.hz); true }
                            else -> false
                        }
                    }
                    popup.show()
                } else {
                    val popup = PopupMenu(it.context, it)
                    popup.menu.add(0, 1, 0, "Delete")
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            1 -> { onDeleteHopperFrequency(item.hz); true }
                            else -> false
                        }
                    }
                    popup.show()
                }
                true
            }
        }
    }

    inner class PresetVH(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.txt_preset_name)
        private val moduleText: TextView = view.findViewById(R.id.txt_preset_module)
        private val moreBtn: ImageButton = view.findViewById(R.id.btn_more)
        fun bind(item: SettingsItem.PresetItem) {
            nameText.text = item.preset.name
            moduleText.text = item.preset.module
            moreBtn.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, "Edit")
                popup.menu.add(0, 2, 1, "Delete")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { onEditPreset(item.index); true }
                        2 -> { onDeletePreset(item.index); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    inner class HoppingPresetVH(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.txt_preset_name)
        private val moduleText: TextView = view.findViewById(R.id.txt_preset_module)
        private val moreBtn: ImageButton = view.findViewById(R.id.btn_more)
        fun bind(item: SettingsItem.HoppingPresetItem) {
            nameText.text = item.name
            moduleText.text = "Modulation"
            moreBtn.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, "Delete")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> { onDeleteHoppingPreset(item.index); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    inner class AddButtonVH(view: View) : RecyclerView.ViewHolder(view) {
        private val btn: Button = view.findViewById(R.id.btn_add)
        fun bind(item: SettingsItem.AddButton) {
            btn.text = item.label
            btn.setOnClickListener { onAddClicked(item.type) }
        }
    }
}
