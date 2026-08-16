package com.sbro.emucorec.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun FolderPickerDialog(
    title: String,
    initialDir: File? = null,
    onFolderSelected: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentDir by remember { mutableStateOf(initialDir ?: File("/storage/emulated/0")) }

    val subdirs = remember(currentDir) {
        currentDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (currentDir.absolutePath != "/storage/emulated/0") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentDir = currentDir.parentFile ?: File("/storage/emulated/0")
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("..")
                    }
                    HorizontalDivider()
                }
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(subdirs, key = { it.absolutePath }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(dir.name)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onFolderSelected(currentDir) }) {
                Text("Обрати цю теку")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        }
    )
}
