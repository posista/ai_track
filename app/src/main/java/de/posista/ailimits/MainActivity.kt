package de.posista.ailimits

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val light = lightColorScheme(
    primary = Color(0xFF245D55), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDECE2), onPrimaryContainer = Color(0xFF103C36),
    background = Color(0xFFF3F6F5), onBackground = Color(0xFF142C28),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF142C28),
    surfaceVariant = Color(0xFFE4EEEB), onSurfaceVariant = Color(0xFF425952),
    outlineVariant = Color(0xFFD8E3DF)
)
private val dark = darkColorScheme(
    primary = Color(0xFFA2D6C8), onPrimary = Color(0xFF103C36),
    primaryContainer = Color(0xFF244C44), onPrimaryContainer = Color(0xFFCDECE2),
    background = Color(0xFF101B19), onBackground = Color(0xFFE1EEEA),
    surface = Color(0xFF172522), onSurface = Color(0xFFE1EEEA),
    surfaceVariant = Color(0xFF263833), onSurfaceVariant = Color(0xFFB8CCC4),
    outlineVariant = Color(0xFF364D45)
)

class MainActivity : ComponentActivity() {
    private val app get() = application as LimitApplication
    private var sharedText by mutableStateOf<String?>(null)
    private var listenerEnabled by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)
    private var zone by mutableStateOf(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeShare(intent)
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshPermissions()
                app.checkDue()
            }
            val colors = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light
            MaterialTheme(colorScheme = colors) {
                Dashboard(
                    app, zone, listenerEnabled, notificationsEnabled, sharedText,
                    onShareHandled = { sharedText = null },
                    onEnableListener = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onEnableReminders = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            !getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)
                        ) {
                            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications", true).apply()
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    override fun onResume() {
        super.onResume()
        zone = ZoneId.systemDefault()
        refreshPermissions()
        app.checkDue()
    }

    private fun refreshPermissions() {
        listenerEnabled = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        notificationsEnabled = app.canNotify()
    }

    private fun takeShare(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.take(ResetParser.MAX_INPUT)?.toString() ?: ""
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }
}

@Composable
private fun Dashboard(
    app: LimitApplication,
    zone: ZoneId,
    listenerEnabled: Boolean,
    notificationsEnabled: Boolean,
    sharedText: String?,
    onShareHandled: () -> Unit,
    onEnableListener: () -> Unit,
    onEnableReminders: () -> Unit
) {
    val flow = remember(app) { app.store.dao.observeAll() }
    val records by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }
    var importText by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf<ManualEntry?>(null) }
    var delete by remember { mutableStateOf<LimitRecord?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.contentResolver
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    readResetFile(resolver.openInputStream(uri) ?: throw IOException())
                }
                importText = input
                feedback = null
            } catch (_: IOException) {
                feedback = "Die Datei konnte nicht geöffnet werden."
            } catch (_: IllegalArgumentException) {
                feedback = "Die Datei ist zu groß oder hat ein ungültiges Format."
            } catch (_: SecurityException) {
                feedback = "Android hat den Zugriff auf die Datei verweigert."
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val active = records.map(LimitRecord::asCandidate)
                .filter { ResetParser.isFuture(it.resetAt, Instant.now()) }
            try {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)?.use { writeResetFile(it, active) } ?: throw IOException()
                }
                feedback = "${active.size} Rücksetzzeiten exportiert."
            } catch (_: IOException) {
                feedback = "Die Datei konnte nicht gespeichert werden."
            } catch (_: IllegalArgumentException) {
                feedback = "Der Export überschreitet die maximale Dateigröße."
            } catch (_: SecurityException) {
                feedback = "Android hat den Zugriff auf die Datei verweigert."
            }
        }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            importText = sharedText
            manual = null
            delete = null
            onShareHandled()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(15_000)
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.widthIn(max = 660.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("AI Limit Notifier", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
                Text("Rücksetzzeiten für deine KI-Nutzungslimits",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importText = "" }, modifier = Modifier.weight(1f)) {
                        Text("Text übernehmen")
                    }
                    OutlinedButton(onClick = { manual = ManualEntry() }, modifier = Modifier.weight(1f)) {
                        Text("Zeit eintragen")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { importFile.launch(arrayOf("text/plain", "application/octet-stream")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Datei öffnen") }
                    OutlinedButton(
                        onClick = { exportFile.launch("ai-limit-notifier.txt") },
                        enabled = records.any { ResetParser.isFuture(it.resetAt, now) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Zeiten exportieren") }
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Android-Benachrichtigungen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (listenerEnabled) "Automatische Erkennung aktiv. Sie benötigt eine Limitmeldung des Anbieters mit Rücksetzzeit."
                            else "Mit Lesezugriff kann die App Limitmeldungen von ChatGPT und Claude lokal auswerten. Android erteilt diesen Zugriff systemweit.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = onEnableListener) {
                            Text(if (listenerEnabled) "Lesezugriff verwalten" else "Lesezugriff einrichten")
                        }
                        HorizontalDivider()
                        Text(
                            if (notificationsEnabled) "Reset-Erinnerungen sind erlaubt."
                            else "Erlaube Reset-Erinnerungen, um zum erfassten Zeitpunkt eine Meldung zu erhalten.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!notificationsEnabled) {
                            TextButton(onClick = onEnableReminders) { Text("Erinnerungen erlauben") }
                        } else {
                            TextButton(onClick = {
                                feedback = if (app.showTestNotification()) "Test-Erinnerung gesendet."
                                else "Android blockiert die Test-Erinnerung."
                            }) { Text("Test-Erinnerung senden") }
                        }
                    }
                }
                Text("Nutzungslimits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                for (provider in Provider.entries) {
                    ProviderCard(
                        provider, records.filter { it.provider == provider }, now, zone,
                        onEdit = { window, record -> manual = ManualEntry(provider, window, existing = record) },
                        onDelete = { delete = it }
                    )
                }
            }
        }
    }
    importText?.let { text ->
        ImportDialog(text, zone, onClose = { importText = null }, onCompleteDate = { draft ->
            importText = null
            manual = ManualEntry(draft.provider, draft.window, undated = draft)
        }, onSave = { candidates ->
            scope.launch {
                val count = app.save(candidates, Source.SHARED, Instant.now())
                feedback = "$count Rücksetzzeiten übernommen."
                importText = null
            }
        })
    }
    manual?.let { initial ->
        ManualDialog(initial, zone, onClose = { manual = null }, onSave = { candidate ->
            scope.launch {
                val source = if (initial.undated == null) Source.MANUAL else Source.SHARED
                app.save(listOf(candidate), source, Instant.now())
                manual = null
            }
        })
    }
    delete?.let { record ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Rücksetzzeit entfernen?") },
            text = { Text(listOf(record.provider.title, record.window.title, record.scope)
                .filter(String::isNotBlank).joinToString(" · ")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.delete(record); delete = null }
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: Provider, records: List<LimitRecord>, now: Instant, zone: ZoneId,
    onEdit: (LimitWindow, LimitRecord?) -> Unit, onDelete: (LimitRecord) -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(provider.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val windows = LimitWindow.entries.filter { window ->
                window != LimitWindow.UNKNOWN || records.any { it.window == window }
            }
            for (window in windows) {
                val matches = records.filter { it.window == window }.sortedBy(LimitRecord::scopeId)
                val entries: List<LimitRecord?> = matches.ifEmpty { listOf(null) }
                for (record in entries) {
                    HorizontalDivider()
                    val label = listOf(window.title, record?.scope.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(record?.let { remainingTime(it.resetAt, now) } ?: "Unbekannt",
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (record != null) {
                        val status = when {
                            record.resetAt <= now -> "Reset fällig · Verfügbarkeit unbestätigt"
                            record.reached -> "Limit laut Quelle erreicht"
                            else -> "Reset erfasst · Limitstatus unbekannt"
                        }
                        Detail(status)
                        Detail("Reset ${displayTime(record.resetAt, zone)}")
                        Detail("Aktualisiert ${displayTime(record.observedAt, zone)}")
                        Detail("Quelle · ${record.source.title}")
                        Detail("Zuverlässigkeit · ${record.source.confidence}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onEdit(window, record) }) {
                            Text(if (record == null) "Rücksetzzeit eintragen" else "Bearbeiten")
                        }
                        if (record != null) TextButton(onClick = { onDelete(record) }) { Text("Entfernen") }
                    }
                }
                if (matches.isNotEmpty()) {
                    TextButton(onClick = { onEdit(window, null) }) { Text("Kontingent hinzufügen") }
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ImportDialog(
    initial: String, zone: ZoneId, onClose: () -> Unit,
    onCompleteDate: (UndatedReset) -> Unit, onSave: (List<ResetCandidate>) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var provider by remember { mutableStateOf<Provider?>(null) }
    val parsed = remember(text, provider, zone) { ResetParser.readShared(text, provider, Instant.now(), zone) }
    val undated = remember(text, provider, zone) {
        if (parsed.isEmpty()) ResetParser.readUndated(text, provider, zone) else null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Reset aus Text übernehmen") },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(ResetParser.MAX_FILE_INPUT) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Limitmeldung") }, minLines = 2, maxLines = 5
                )
                Detail("Falls der Dienst im Text fehlt, wähle ihn hier. Zeiten ohne Offset gelten in ${zone.id}.")
                ProviderChoices(provider) { provider = if (provider == it) null else it }
                if (undated != null) {
                    Text("Erkannte Uhrzeit ${undated.time} · ${undated.zone.id}")
                    TextButton(onClick = { onCompleteDate(undated) }) { Text("Datum ergänzen") }
                } else if (parsed.isEmpty()) {
                    Text("Keine eindeutige zukünftige Rücksetzzeit erkannt.")
                    Detail("Beispiel · Codex | wöchentlich | JJJJ-MM-TTThh:mm+02:00")
                } else {
                    Text("Erkannte Rücksetzzeiten", style = MaterialTheme.typography.titleSmall)
                    parsed.forEach {
                        val label = listOf(it.provider.title, it.window.title, it.scope)
                            .filter(String::isNotBlank).joinToString(" · ")
                        Detail("$label\n${displayTime(it.resetAt, zone)}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isNotEmpty(), onClick = {
                val valid = parsed.filter { ResetParser.isFuture(it.resetAt, Instant.now()) }
                if (valid.isNotEmpty()) onSave(valid) else onClose()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } }
    )
}

private data class ManualEntry(
    val provider: Provider = Provider.CHATGPT,
    val window: LimitWindow = LimitWindow.SHORT,
    val existing: LimitRecord? = null,
    val undated: UndatedReset? = null
)
private val inputFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
private val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu, HH:mm z", Locale.GERMANY)

private fun displayTime(time: Instant, zone: ZoneId) = outputFormatter.format(time.atZone(zone))

@Composable
private fun ManualDialog(initial: ManualEntry, zone: ZoneId, onClose: () -> Unit, onSave: (ResetCandidate) -> Unit) {
    var provider by remember { mutableStateOf(initial.provider) }
    var window by remember { mutableStateOf(initial.window) }
    val entryZone = initial.undated?.zone ?: zone
    var text by remember { mutableStateOf(initial.existing?.resetAt?.atOffset(
        entryZone.rules.getOffset(initial.existing.resetAt)
    )?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).orEmpty()) }
    var scope by remember { mutableStateOf(initial.existing?.scope ?: initial.undated?.scope.orEmpty()) }
    val context = LocalContext.current
    val parsed = remember(text, entryZone) { ResetParser.parseManual(text, entryZone) }
    val valid = parsed?.takeIf { ResetParser.isFuture(it, Instant.now()) && ResetParser.validScope(scope) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rücksetzzeit eintragen") },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial.existing == null) {
                    ProviderChoices(provider) { provider = it }
                    for (option in LimitWindow.entries) {
                        FilterChip(selected = window == option, onClick = { window = option }, label = { Text(option.title) })
                    }
                    OutlinedTextField(
                        value = scope, onValueChange = { scope = it.take(ResetParser.MAX_SCOPE_LENGTH) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Kontingentname (optional)") },
                        singleLine = true, isError = !ResetParser.validScope(scope)
                    )
                } else {
                    Detail(listOf(provider.title, window.title, scope).filter(String::isNotBlank).joinToString(" · "))
                }
                initial.undated?.let { if (text.isBlank()) Detail("Erkannte Uhrzeit ${it.time} · ${entryZone.id}") }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(64) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("JJJJ-MM-TT HH:mm") }, singleLine = true,
                    isError = text.isNotBlank() && valid == null
                )
                TextButton(onClick = {
                    val start = parsed?.atZone(entryZone)?.toLocalDateTime()
                        ?: LocalDateTime.now(entryZone).plusHours(1)
                    val draftTime = initial.undated?.time?.takeIf { text.isBlank() }
                    DatePickerDialog(context, { _, year, month, day ->
                        if (draftTime != null) {
                            text = inputFormatter.format(java.time.LocalDate.of(year, month + 1, day).atTime(draftTime))
                        } else {
                            TimePickerDialog(context, { _, hour, minute ->
                                text = inputFormatter.format(LocalDateTime.of(year, month + 1, day, hour, minute))
                            }, start.hour, start.minute, true).show()
                        }
                    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                }) { Text(if (initial.undated != null && text.isBlank()) "Datum wählen" else "Datum und Uhrzeit wählen") }
                Detail("Zeitzone ${entryZone.id}. ISO-Zeit mit Offset möglich. Wähle einen eindeutigen Zeitpunkt in den nächsten 30 Tagen.")
                if (valid != null) Detail(displayTime(valid, zone))
            }
        },
        confirmButton = {
            TextButton(enabled = valid != null, onClick = {
                if (valid != null && ResetParser.isFuture(valid, Instant.now())) {
                    onSave(ResetCandidate(provider, window, valid,
                        initial.undated?.reached ?: initial.existing?.reached ?: false, scope.trim()))
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderChoices(selected: Provider?, onSelect: (Provider) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Provider.entries.forEach { provider ->
            FilterChip(selected = selected == provider, onClick = { onSelect(provider) }, label = { Text(provider.title) })
        }
    }
}
