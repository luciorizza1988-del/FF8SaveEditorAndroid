package it.luciorizza.ff8saveeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream

data class ImportedFile(val name: String, val bytes: ByteArray, val backup: File, val summary: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SaveEditorHome() } }
    }

    @Composable
    private fun SaveEditorHome() {
        var selectedName by remember { mutableStateOf("Nessun salvataggio selezionato") }
        var status by remember { mutableStateOf("Seleziona un file di salvataggio FF8 per iniziare.") }
        var backupFile by remember { mutableStateOf<File?>(null) }
        var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { destination: Uri? ->
            val bytes = pendingBytes
            if (destination != null && bytes != null) {
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                        ?: error("Impossibile aprire il file di destinazione")
                }.onSuccess { status = "Copia esportata. Il file originale non è stato modificato." }
                    .onFailure { status = "Esportazione non riuscita: ${it.localizedMessage}" }
            }
        }

        val openLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Impossibile leggere il file")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "ff8-save"
                    val backupDir = File(filesDir, "backups").apply { mkdirs() }
                    val backup = File(backupDir, "${System.currentTimeMillis()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    FileOutputStream(backup).use { it.write(bytes) }
                    ImportedFile(name, bytes, backup, FF8SaveScanner.describe(bytes))
                }.onSuccess { imported ->
                    selectedName = imported.name
                    pendingBytes = imported.bytes
                    backupFile = imported.backup
                    val preview = FF8SaveReader.read(imported.bytes)
                    status = buildString {
                        append(imported.summary)
                        if (preview != null) {
                            append("\n\n")
                            append(FF8SaveReader.format(preview))
                        } else if (imported.bytes.size >= 131072) {
                            for (slot in 0 until 15) {
                                val offset = 8192 + slot * 8192
                                val cardPreview = FF8SaveReader.read(imported.bytes, offset)
                                if (cardPreview != null) {
                                    append("\n\nSlot ").append(slot + 1).append(":\n")
                                    append(FF8SaveReader.format(cardPreview))
                                }
                            }
                        }
                    }
                }.onFailure { status = "Apertura non riuscita: ${it.localizedMessage}" }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("FINAL FANTASY VIII", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Save Editor", style = MaterialTheme.typography.headlineLarge)
                Text("Strumento Android per i salvataggi di FF8")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SALVATAGGIO", style = MaterialTheme.typography.labelMedium)
                        Text(selectedName, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Apri salvataggio") }
                OutlinedButton(
                    onClick = {
                        val bytes = pendingBytes
                        if (bytes != null) {
                            pendingBytes = bytes
                            exportLauncher.launch("FF8-save-copy")
                        }
                    },
                    enabled = pendingBytes != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Esporta copia del file") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backup automatico:", style = MaterialTheme.typography.labelLarge)
                    Text(if (backupFile?.exists() == true) "Creato" else "In attesa")
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Lavori in corso", style = MaterialTheme.typography.titleMedium)
                        Text("• Importazione file e backup locale")
                        Text("• Analisi formato dei salvataggi PlayStation")
                        Text("• Integrazione del motore di lettura/scrittura Hyne")
                        Text("• Editor personaggi, GF, magie, oggetti e carte")
                    }
                }
                Text(
                    "Nota: questa prima versione è una base tecnica. Non modifica ancora i valori del gioco.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
