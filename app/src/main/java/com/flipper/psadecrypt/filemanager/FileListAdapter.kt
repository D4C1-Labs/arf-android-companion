package com.flipper.psadecrypt.filemanager

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flipper.psadecrypt.R
import com.flipper.psadecrypt.storage.FlipperFile

class FileListAdapter(
    private val onItemClick: (FlipperFile) -> Unit,
    private val onDownload: (FlipperFile) -> Unit,
    private val onDelete: (FlipperFile) -> Unit
) : ListAdapter<FlipperFile, FileListAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FlipperFile>() {
            override fun areItemsTheSame(a: FlipperFile, b: FlipperFile) = a.name == b.name
            override fun areContentsTheSame(a: FlipperFile, b: FlipperFile) = a == b
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.txt_file_icon)
        val name: TextView = view.findViewById(R.id.txt_file_name)
        val size: TextView = view.findViewById(R.id.txt_file_size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = getItem(position)

        holder.name.text = file.name

        if (file.isDirectory) {
            holder.icon.text = "📁"
            holder.size.visibility = View.GONE
        } else {
            holder.icon.text = "📄"
            holder.size.text = formatSize(file.size)
            holder.size.visibility = View.VISIBLE
        }

        // Tap normal — naviguer dans dossier ou ouvrir
        holder.itemView.setOnClickListener { onItemClick(file) }

        // Long press — menu Delete avec confirmation
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            val type = if (file.isDirectory) "folder" else "file"

            AlertDialog.Builder(ctx)
                .setTitle("Delete $type")
                .setMessage("Delete \"${file.name}\" ?")
                .setPositiveButton("Delete") { _, _ -> onDelete(file) }
                .setNegativeButton("Cancel", null)
                .show()

            true
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
