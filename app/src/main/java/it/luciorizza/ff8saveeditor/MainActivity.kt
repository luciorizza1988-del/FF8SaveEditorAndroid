package it.luciorizza.ff8saveeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.material3.OutlinedTextField
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
        var gilValue by remember { mutableStateOf("1000000") }
        var charSlot by remember { mutableStateOf("0") }
        var hpValue by remember { mutableStateOf("9999") }
        var maxHpValue by remember { mutableStateOf("9999") }
        var expValue by remember { mutableStateOf("1000000") }
        var spellIndex by remember { mutableStateOf("1") }
        var spellQuantity by remember { mutableStateOf("100") }
        var junctionField by remember { mutableStateOf("0") }
        var junctionMagicId by remember { mutableStateOf("19") }
        var itemId by remember { mutableStateOf("1") }
        var itemQuantity by remember { mutableStateOf("99") }
        var cardIndex by remember { mutableStateOf("0") }
        var cardQuantity by remember { mutableStateOf("1") }
        var cardOwned by remember { mutableStateOf(true) }

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

        val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            if (treeUri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: error("Cartella non accessibile")
                    fun findCandidate(folder: DocumentFile, depth: Int): Pair<String, ByteArray>? {
                        if (depth > 5) return null
                        for (entry in folder.listFiles()) {
                            if (entry.isDirectory) {
                                val nested = findCandidate(entry, depth + 1)
                                if (nested != null) return nested
                            } else if (entry.isFile) {
                                val bytes = contentResolver.openInputStream(entry.uri)?.use { it.readBytes() } ?: continue
                                var offset = 0
                                if (bytes.size >= 131072) {
                                    for (slot in 0 until 15) {
                                        val candidate = 8192 + slot * 8192
                                        if (FF8SaveReader.read(bytes, candidate) != null) { offset = candidate; break }
                                    }
                                }
                                if (FF8SaveReader.read(bytes, offset) != null) return (entry.name ?: "salvataggio-FF8") to bytes
                            }
                        }
                        return null
                    }
                    val found = findCandidate(root, 0) ?: error("Nella cartella scelta non trovo un salvataggio FF8 valido.")
                    val (name, bytes) = found
                    var offset = 0
                    if (bytes.size >= 131072) {
                        for (slot in 0 until 15) {
                            val candidate = 8192 + slot * 8192
                            if (FF8SaveReader.read(bytes, candidate) != null) { offset = candidate; break }
                        }
                    }
                    val backupDir = File(filesDir, "backups").apply { mkdirs() }
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val backup = File(backupDir, System.currentTimeMillis().toString() + "-" + safeName)
                    FileOutputStream(backup).use { it.write(bytes) }
                    Triple(name, bytes, backup) to offset
                }.onSuccess { pair ->
                    val file = pair.first
                    val offset = pair.second
                    selectedName = file.first
                    pendingBytes = file.second
                    backupFile = file.third
                    saveOffset = offset
                    status = FF8SaveReader.read(file.second, offset)?.let { "Salvataggio FF8 importato dalla cartella.\n\n" + FF8SaveReader.format(it) } ?: "File importato, ma anteprima FF8 non disponibile."
                }.onFailure { status = "Importazione cartella non riuscita: " + it.localizedMessage }
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
                OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Importa dalla cartella")
                }
                OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Importa dalla cartella")
                }
                Text("MODIFICA MANUALE", style = MaterialTheme.typography.headlineSmall)
                Text("Scegli tu i valori. Gli indici dei personaggi vanno da 0 a 7.")
                OutlinedTextField(value = gilValue, onValueChange = { gilValue = it }, label = { Text("Gil (0–4.294.967.295)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { gilValue.toLongOrNull()?.let { v -> applyEdit { b, o -> FF8SaveWriter.updateGil(b, o, v) } } ?: run { status = "Inserisci un numero valido per i Gil." } }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica Gil scelti") }

                Text("PERSONAGGIO: HP ED ESPERIENZA", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = charSlot, onValueChange = { charSlot = it }, label = { Text("Indice personaggio (0–7)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = hpValue, onValueChange = { hpValue = it }, label = { Text("HP attuali (0–65535)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxHpValue, onValueChange = { maxHpValue = it }, label = { Text("HP massimi (0–65535)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = expValue, onValueChange = { expValue = it }, label = { Text("Esperienza (0–4.294.967.295)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val c = charSlot.toIntOrNull(); val hp = hpValue.toIntOrNull(); val mhp = maxHpValue.toIntOrNull(); val xp = expValue.toLongOrNull()
                    if (c == null || hp == null || mhp == null || xp == null) status = "Controlla i valori del personaggio."
                    else applyEdit { b, o -> FF8SaveWriter.updateCharacter(b, o, c, xp, hp, mhp) }
                }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica HP ed esperienza") }

                Text("MAGIE: QUANTITÀ MANUALE", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = spellIndex, onValueChange = { spellIndex = it }, label = { Text("ID magia (0–31)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = spellQuantity, onValueChange = { spellQuantity = it }, label = { Text("Quantità (0–100)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val c = charSlot.toIntOrNull(); val s = spellIndex.toIntOrNull(); val q = spellQuantity.toIntOrNull()
                    if (c == null || s == null || q == null) status = "Controlla personaggio, ID magia e quantità."
                    else applyEdit { b, o -> FF8SaveWriter.updateMagicStock(b, o, c, s, q) }
                }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica quantità magia al personaggio") }

                Text("JUNCTION MANUALE", style = MaterialTheme.typography.titleLarge)
                Text("Slot 0–8: statistiche; 9: attacco elementale; 10: attacco status; 11–14: difesa elementale; 15–18: difesa status. ID magia 0–56.")
                OutlinedTextField(value = junctionField, onValueChange = { junctionField = it }, label = { Text("Slot Junction (0–18)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = junctionMagicId, onValueChange = { junctionMagicId = it }, label = { Text("ID magia da assegnare (0–56)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val c = charSlot.toIntOrNull(); val f = junctionField.toIntOrNull(); val id = junctionMagicId.toIntOrNull()
                    if (c == null || f == null || id == null) status = "Controlla i valori Junction."
                    else applyEdit { b, o -> FF8SaveWriter.updateJunction(b, o, c, f, id) }
                }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica Junction scelto") }

                Text("OGGETTI: QUANTITÀ MANUALE", style = MaterialTheme.typography.titleLarge)
                Text("Inserisci l'ID numerico dell'oggetto (1–255) e la quantità (0–99).")
                OutlinedTextField(value = itemId, onValueChange = { itemId = it }, label = { Text("ID oggetto") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = itemQuantity, onValueChange = { itemQuantity = it }, label = { Text("Quantità") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val id = itemId.toIntOrNull(); val q = itemQuantity.toIntOrNull()
                    if (id == null || q == null) status = "Controlla ID oggetto e quantità."
                    else applyEdit { b, o -> FF8SaveWriter.updateItemQuantity(b, o, id, q) }
                }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica quantità oggetto") }

                Text("TRIPLE TRIAD: MODIFICA CARTA", style = MaterialTheme.typography.titleLarge)
                Text("Carte normali: indice 0–76; quantità 0–127. La carta viene impostata come posseduta.")
                OutlinedTextField(value = cardIndex, onValueChange = { cardIndex = it }, label = { Text("Indice carta (0–76)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cardQuantity, onValueChange = { cardQuantity = it }, label = { Text("Quantità carta (0–127)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val index = cardIndex.toIntOrNull(); val q = cardQuantity.toIntOrNull()
                    if (index == null || q == null) status = "Controlla indice e quantità della carta."
                    else applyEdit { b, o -> FF8SaveWriter.updateCard(b, o, index, q, true) }
                }, enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) { Text("Applica carta normale") }

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
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeJunctionStats(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Assegna Ultima a tutti gli slot Junction")
                }
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
                Text("TRIPLE TRIAD", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { applyEdit { b, o -> FF8SaveWriter.maximizeTripleTriad(b, o) } },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Sblocca tutte le carte (127 copie, rare incluse)")
                }
                Text("ESPORTAZIONE", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { exportLauncher.launch("Final-Fantasy-VIII-modificato.mcd") },
                    enabled = pendingBytes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Esporta copia modificata")
                }
                Text("Nota: il pulsante Junction assegna Ultima a tutti gli slot Junction della squadra attiva. Triple Triad assegna tutte le 77 carte standard (127 copie) e tutte le 33 rare.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
