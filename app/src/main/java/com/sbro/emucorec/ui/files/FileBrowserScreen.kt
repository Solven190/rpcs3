package com.sbro.emucorec.ui.files

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sbro.emucorec.R
import com.sbro.emucorec.core.DocumentPathResolver
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.ui.common.ScreenTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class BrowserMode { License, Pkg, Firmware }

private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun findDefaultRoot(): File {
    val ps3 = File("/storage/emulated/0/PS3")
    if (ps3.isDirectory) return ps3
    return File("/storage/emulated/0")
}

@Composable
fun FileBrowserScreen(
    mode: BrowserMode,
    onFilesPicked: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val extensions = when (mode) {
        BrowserMode.License -> setOf("rap", "edat")
        BrowserMode.Pkg -> setOf("pkg", "iso")
        BrowserMode.Firmware -> setOf("pup")
    }
    val multiple = mode == BrowserMode.Pkg

    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        allFilesAccess = hasAllFilesAccess()
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            allFilesPermissionLauncher.launch(intent)
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            preferences.setPackagesFolder(context, uri)
        }
    }

    var currentDir by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<File>() }

    fun loadDir(dir: File) {
        selected.clear()
        currentDir = dir
        entries = dir.listFiles()
            ?.filter { it.isDirectory || it.extension.lowercase() in extensions }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    LaunchedEffect(allFilesAccess) {
        if (allFilesAccess) {
            val saved = preferences.packagesFolderUriAsUri()?.path?.let {
                File(it.removePrefix("/tree/").replace(":", "/"))
            }
            val start = when {
                saved?.isDirectory == true -> saved
                else -> findDefaultRoot()
            }
            loadDir(start)
        }
    }

    val goUp: () -> Unit = {
        val parent = currentDir?.parentFile
        if (parent != null && parent.absolutePath.startsWith("/storage/")) {
            loadDir(parent)
        } else {
            onDismiss()
        }
    }

    BackHandler(onBack = goUp)

    fun pickSingle(file: File) {
        scope.launch {
            busy = true
            val path = withContext(Dispatchers.IO) {
                file.absolutePath
            }
            busy = false
            onFilesPicked(listOf(path))
        }
    }

    fun pickMultiple() {
        scope.launch {
            busy = true
            val paths = withContext(Dispatchers.IO) {
                selected.map { it.absolutePath }
            }
            busy = false
            if (paths.isNotEmpty()) onFilesPicked(paths)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            ScreenTopBar(
                title = when (mode) {
                    BrowserMode.License -> stringResource(R.string.file_browser_title_license)
                    BrowserMode.Pkg -> stringResource(R.string.file_browser_title_pkg)
                    BrowserMode.Firmware -> stringResource(R.string.file_browser_title_firmware)
                },
                subtitle = currentDir?.name
                    ?: stringResource(R.string.file_browser_no_folder),
                onBackClick = goUp,
                actions = {
                    if (!allFilesAccess) {
                        TextButton(onClick = ::requestAllFilesAccess) {
                            Text("...")
                        }
                    }
                }
            )
            Spacer(Modifier.height(10.dp))

            if (!allFilesAccess) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.file_browser_choose_folder_hint),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = ::requestAllFilesAccess) {
                        Text(stringResource(R.string.file_browser_choose_folder))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { treePicker.launch(null) }) {
                        Text(stringResource(R.string.file_browser_change_folder))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries, key = { it.absolutePath }) { file ->
                        val isDir = file.isDirectory
                        val checked = file in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        isDir -> loadDir(file)
                                        multiple -> if (checked) selected.remove(file) else selected.add(file)
                                        else -> pickSingle(file)
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!isDir) {
                                    Text(
                                        text = formatFileSize(file.length()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (multiple && !isDir) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (it) selected.add(file) else selected.remove(file)
                                })
                            }
                        }
                        HorizontalDivider()
                    }
                }
                if (multiple && selected.isNotEmpty()) {
                    Button(
                        onClick = ::pickMultiple,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 24.dp)
                    ) {
                        Text(stringResource(R.string.file_browser_install, selected.size))
                    }
                } else {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
