package com.flipper.psadecrypt.filemanager

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flipper.psadecrypt.MainActivity
import com.flipper.psadecrypt.R
import com.flipper.psadecrypt.storage.FlipperFile
import com.flipper.psadecrypt.storage.FlipperStorageApi
import kotlinx.coroutines.*

class FileManagerFragment : Fragment() {

    private var currentPath = "/ext"
    private val pathStack = mutableListOf<String>()

    private lateinit var adapter: FileListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var pathText: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var notConnectedText: TextView
    private lateinit var bottomBar: View

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val storageApi: FlipperStorageApi?
        get() = (activity as? MainActivity)?.storageApi

    // SAF launchers — initialisés dans onCreate() pour éviter le crash sur Android 13+
    private lateinit var uploadFilePicker: androidx.activity.result.ActivityResultLauncher<Array<String>>
    private var pendingDownloadFile: FlipperFile? = null
    private lateinit var downloadSavePicker: androidx.activity.result.ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enregistrement des launchers SAF ici — obligatoire avant onStart()
        uploadFilePicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { handleUpload(it) } }

        downloadSavePicker = registerForActivityResult(
            ActivityResultContracts.CreateDocument("*/*")
        ) { uri -> uri?.let { handleDownloadSave(it) } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_file_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pathText = view.findViewById(R.id.txt_current_path)
        loadingProgress = view.findViewById(R.id.progress_loading)
        errorText = view.findViewById(R.id.txt_error)
        notConnectedText = view.findViewById(R.id.txt_not_connected)
        bottomBar = view.findViewById(R.id.bottom_bar)
        recyclerView = view.findViewById(R.id.recycler_files)

        adapter = FileListAdapter(
            onItemClick = { file ->
                if (file.isDirectory) {
                    navigateTo("$currentPath/${file.name}")
                }
            },
            onDownload = { file -> startDownload(file) },
            onDelete = { file -> doDelete(file) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btn_up).setOnClickListener { navigateUp() }
        view.findViewById<View>(R.id.btn_refresh).setOnClickListener { loadFiles() }
        view.findViewById<View>(R.id.btn_upload).setOnClickListener { startUpload() }
        view.findViewById<View>(R.id.btn_mkdir).setOnClickListener { showMkdirDialog() }

        loadFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.coroutineContext.cancelChildren()
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!isAdded) return
        if (connected) {
            notConnectedText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            loadFiles()
        } else {
            notConnectedText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            bottomBar.visibility = View.GONE
            loadingProgress.visibility = View.GONE
            errorText.visibility = View.GONE
        }
    }

    // --- Navigation ---

    private fun navigateTo(path: String) {
        pathStack.add(currentPath)
        currentPath = normalizePath(path)
        loadFiles()
    }

    private fun navigateUp() {
        if (pathStack.isNotEmpty()) {
            currentPath = pathStack.removeAt(pathStack.size - 1)
            loadFiles()
        } else if (currentPath != "/" && currentPath != "/ext") {
            val parent = currentPath.substringBeforeLast("/")
            currentPath = if (parent.isEmpty()) "/" else parent
            loadFiles()
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace("//", "/").trimEnd('/')
    }

    // --- File listing ---

    private fun loadFiles() {
        val api = storageApi
        if (api == null) {
            notConnectedText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            bottomBar.visibility = View.GONE
            return
        }

        notConnectedText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE

        pathText.text = currentPath
        loadingProgress.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        adapter.submitList(emptyList())

        scope.launch {
            val result = withContext(Dispatchers.IO) { api.list(currentPath) }
            if (!isAdded) return@launch

            loadingProgress.visibility = View.GONE

            result.onSuccess { files ->
                // Sort: directories first, then alphabetical
                val sorted = files.sortedWith(compareByDescending<FlipperFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                adapter.submitList(sorted)
                if (files.isEmpty()) {
                    errorText.text = "Empty directory"
                    errorText.visibility = View.VISIBLE
                }
            }.onFailure { e ->
                errorText.text = "Error: ${e.message}"
                errorText.visibility = View.VISIBLE
                log("List failed: ${e.message}")
            }
        }
    }

    // --- Download ---

    private fun startDownload(file: FlipperFile) {
        pendingDownloadFile = file
        downloadSavePicker.launch(file.name)
    }

    private fun handleDownloadSave(uri: Uri) {
        val file = pendingDownloadFile ?: return
        pendingDownloadFile = null
        val api = storageApi ?: return

        val flipperPath = "$currentPath/${file.name}"

        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Downloading")
            .setMessage("${file.name}\n0 / ${formatSize(file.size)}")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val tempFile = java.io.File(requireContext().cacheDir, "download_tmp_${System.currentTimeMillis()}")
            val result = withContext(Dispatchers.IO) {
                api.download(flipperPath, tempFile) { downloaded, total ->
                    val msg = "${file.name}\n${formatSize(downloaded)} / ${formatSize(total)}"
                    scope.launch { progressDialog.setMessage(msg) }
                }
            }
            progressDialog.dismiss()

            if (!isAdded) {
                tempFile.delete()
                return@launch
            }

            result.onSuccess {
                // Copy temp file to SAF uri
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    tempFile.delete()
                }
                Toast.makeText(requireContext(), "Downloaded: ${file.name}", Toast.LENGTH_SHORT).show()
                log("Downloaded: $flipperPath")
            }.onFailure { e ->
                tempFile.delete()
                Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                log("Download failed: ${e.message}")
            }
        }
    }

    // --- Upload ---

    private fun startUpload() {
        uploadFilePicker.launch(arrayOf("*/*"))
    }

    private fun handleUpload(uri: Uri) {
        if (!isAdded) return
        val api = storageApi ?: return
        val ctx = requireContext()

        // Get filename from URI
        val fileName = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: "uploaded_file"

        val flipperPath = "$currentPath/$fileName"

        val progressDialog = AlertDialog.Builder(ctx)
            .setTitle("Uploading")
            .setMessage(fileName)
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch {
            val tempFile = java.io.File(ctx.cacheDir, "upload_tmp_${System.currentTimeMillis()}")

            val result = withContext(Dispatchers.IO) {
                // Copy URI content to temp file
                ctx.contentResolver.openInputStream(uri)?.use { inp ->
                    tempFile.outputStream().use { out -> inp.copyTo(out) }
                }
                api.upload(tempFile, flipperPath) { uploaded, total ->
                    val msg = "$fileName\n${formatSize(uploaded)} / ${formatSize(total)}"
                    scope.launch { progressDialog.setMessage(msg) }
                }
            }
            progressDialog.dismiss()
            tempFile.delete()

            if (!isAdded) return@launch

            result.onSuccess {
                Toast.makeText(ctx, "Uploaded: $fileName", Toast.LENGTH_SHORT).show()
                log("Uploaded: $flipperPath")
                loadFiles() // refresh
            }.onFailure { e ->
                Toast.makeText(ctx, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                log("Upload failed: ${e.message}")
            }
        }
    }

    // --- Delete ---

    private fun confirmDelete(file: FlipperFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete")
            .setMessage("Delete ${if (file.isDirectory) "folder" else "file"} \"${file.name}\"?")
            .setPositiveButton("Delete") { _, _ -> doDelete(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete(file: FlipperFile) {
        val api = storageApi ?: return
        val path = "$currentPath/${file.name}"

        scope.launch {
            val result = withContext(Dispatchers.IO) { api.delete(path, recursive = true) }
            if (!isAdded) return@launch

            result.onSuccess {
                Toast.makeText(requireContext(), "Deleted: ${file.name}", Toast.LENGTH_SHORT).show()
                log("Deleted: $path")
                loadFiles()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                log("Delete failed: ${e.message}")
            }
        }
    }

    // --- Mkdir ---

    private fun showMkdirDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Folder name"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Create Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) doMkdir(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doMkdir(name: String) {
        val api = storageApi ?: return
        val path = "$currentPath/$name"

        scope.launch {
            val result = withContext(Dispatchers.IO) { api.mkdir(path) }
            if (!isAdded) return@launch

            result.onSuccess {
                Toast.makeText(requireContext(), "Created: $name", Toast.LENGTH_SHORT).show()
                log("Created folder: $path")
                loadFiles()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Mkdir failed: ${e.message}", Toast.LENGTH_LONG).show()
                log("Mkdir failed: ${e.message}")
            }
        }
    }

    // --- Helpers ---

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun log(msg: String) {
        (activity as? MainActivity)?.appendLog("[FM] $msg")
    }
}
