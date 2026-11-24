package it.luciorizza.ff8saveeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SaveEditorHome() } }
    }

    @Composable
    private fun SaveEditorHome() {
        var selectedName by remember { mutableStateOf("Nessun salvataggio selezionato") }
        var status by remember { mutableStateOf("Apri una memory card o un salvataggio singolo di Final Fantasy VIII.") }
        var backupFile by remember { mutableStateOf<File?>(null) }
        var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
        var saveOffset by remember { mutableIntStateOf(0) }

        fun applyEdit(edit: (ByteArray, Int) -> FF8SaveWriter.EditResult?) {
            val current = pendingBytes ?: run { status = "Prima apri un salvataggio."; return }
            val result = edit(current, saveOffset)
            if (result == null) {
                status = "Modifica non applicata: formato o posizione del salvataggio non riconosciuti."
            } else {
                pendingBytes = result.bytes
                status = "Modifica applicata alla copia in memoria. Esporta il file per conservarla; l'originale resta intatto."
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { destination: Uri? ->
            val bytes = pendingBytes
            if (destination != null && bytes != null) {
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(bytes) }
                        ?: error("Impossibile aprire il file di destinazione")
                }.onSuccess { status = "Copia modificata esportata. L'originale non è stato sovrascritto." }
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
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "salvataggio-ff8"
                    val backupDir = File(filesDir, "backups").apply { mkdirs() }
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val backup = File(backupDir, "${System.currentTimeMillis()}-$safeName")
                    FileOutputStream(backup).use { it.write(bytes) }
                    var foundOffset = 0
                    if (bytes.size >= 131072) {
                        for (slot in 0 until 15) {
                            val offset = 8192 + slot * 8192
                            if (FF8SaveReader.read(bytes, offset) != null) { foundOffset = offset; break }
                        }
                    }
                    Triple(name, bytes, backup) to foundOffset
                }.onSuccess { (file, offset) ->
                    selectedName = file.first
                    pendingBytes = file.second
                    backupFile = file.third
                    saveOffset = offset
                    val preview = FF8SaveReader.read(file.second, offset)
                    status = buildString {
                        append(FF8SaveScanner.describe(file.second))
                        if (preview != null) {
                            append("\n\n")
                            append(FF8SaveReader.format(preview))
                        } else {
                            append("\n\nSalvataggio FF8 non identificato automaticamente.")
                        }
                        if (offset > 0) append("\nSlot individuato: ${(offset - 8192) / 8192 + 1}")
                    }
                }.onFailure { status = "Apertura non riuscita: ${it.localizedMessage}" }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("FINAL FANTASY VIII", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text("Editor dei salvataggi", style = MaterialTheme.typography.headlineMedium)
                Text("Modifica una copia: il file originale non viene mai sovrascritto.")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("SALVATAGGIO", style = MaterialTheme.typography.labelMedium)
                        Text(selectedName, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text(if (backupFile?.exists() == true) "Backup locale creato" else "Nessun backup",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Button(onClick = { openLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri salvataggio")
                }
                Text("GIL", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.updateGil(b, o, 99999999L) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Imposta Gil al massimo (99.999.999)")
                }
                Text("PERSONAGGI", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeParty(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Massimizza HP ed esperienza della squadra")
                }
                Text("JUNCTION E MAGIE", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeJunctionMagic(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Porta a 100 le magie della squadra")
                }
                Text("GF - GUARDIAN FORCE", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeGf(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Massimizza HP ed esperienza dei GF")
                }
                Text("OGGETTI", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeItems(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Porta a 99 gli oggetti posseduti")
                }
                Text("ESPORTAZIONE", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { exportLauncher.launch("Final-Fantasy-VIII-modificato.mcd") },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Esporta copia modificata")
                }
                Text("Nota: Junction massimizza le scorte di magie dei tre personaggi attivi. GF e carte saranno aggiunti dopo la verifica dei rispettivi dati.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
