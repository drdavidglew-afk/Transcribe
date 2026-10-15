package app.vidscribe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { VidScribeTheme { MainScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Links or files shared into the app from YouTube, a browser, Gallery, etc. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND || vm.busy) return
        vm.mode = Mode.LINK
        val stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null && i.type?.startsWith("text") != true) {
            vm.transcribeFile(stream)
            return
        }
        i.getStringExtra(Intent.EXTRA_TEXT)?.let {
            vm.link = it
            vm.transcribeLink()
        }
    }
}

@Composable
fun VidScribeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var showSettings by remember { mutableStateOf(vm.settings.keyFor(vm.settings.provider).isBlank()) }
    val listState = rememberLazyListState()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::transcribeFile)
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { Toast.makeText(ctx, vm.saveTo(it) ?: "Saved", Toast.LENGTH_SHORT).show() }
    }
    val openPicker = { pickFile.launch(arrayOf("video/*", "audio/*")) }

    // Keep the screen awake while a long job runs, so Android doesn't pause it.
    val activity = ctx as? ComponentActivity
    DisposableEffect(vm.busy) {
        if (vm.busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VidScribe", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val state = vm.state
            val showResult = state is UiState.Working || state is UiState.Done

            if (!showResult) item { ModeSwitch(vm.mode) { vm.mode = it } }

            if (vm.mode == Mode.LINK) item { InputCard(vm, openPicker) }
            else if (!showResult) {
                if (state is UiState.Error) item { ErrorCard(state.message, openPicker) }
                podcastBrowser(vm)
            }

            if (showResult && vm.mode == Mode.PODCASTS && state is UiState.Done) item {
                TextButton(onClick = vm::backFromResult) { Text("← Back to episodes") }
            }

            when (val s = state) {
                UiState.Idle -> if (vm.mode == Mode.LINK) item {
                    Text(
                        "Paste a link to a video from any site, or share one straight to VidScribe from " +
                            "YouTube, your browser or Gallery. You'll get a clean, paragraphed transcript.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else Unit
                is UiState.Working -> item { WorkingCard(s, vm::cancel) }
                is UiState.Error -> if (vm.mode == Mode.LINK) item { ErrorCard(s.message, openPicker) } else Unit
                is UiState.Done -> {
                    item {
                        TranscriptHeader(
                            t = s.transcript,
                            showTimes = vm.showTimes,
                            onToggleTimes = { vm.showTimes = it },
                            onCopy = {
                                vm.transcriptText()?.let { copy(ctx, it) }
                            },
                            onShare = {
                                vm.transcriptText()?.let { share(ctx, s.transcript.title, it) }
                            },
                            onSave = { saveFile.launch(safeFileName(s.transcript.title) + ".txt") }
                        )
                    }
                    items(s.transcript.paragraphs) { p -> ParagraphView(p, vm.showTimes) }
                }
            }
        }
    }

    vm.linkProblem?.let { detail ->
        AlertDialog(
            onDismissRequest = vm::dismissLinkProblem,
            title = { Text("Couldn't get that video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "VidScribe couldn't download the video from this link. It may be private, need a " +
                            "sign-in, or come from a site that blocks downloads.\n\n" +
                            "Please download the video to your phone (most apps have a Download or Save " +
                            "option), then tap Choose file and pick it."
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.dismissLinkProblem(); openPicker() }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = vm::dismissLinkProblem) { Text("Cancel") } }
        )
    }

    if (showSettings) SettingsDialog(vm.settings) { showSettings = false }
}

@Composable
private fun InputCard(vm: MainViewModel, openPicker: () -> Unit) {
    val ctx = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.link,
                onValueChange = { vm.link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video link") },
                placeholder = { Text("https://…") },
                singleLine = true,
                enabled = !vm.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.transcribeLink() }),
                trailingIcon = {
                    if (vm.link.isNotEmpty() && !vm.busy) {
                        IconButton(onClick = { vm.clear() }) { Icon(Icons.Filled.Clear, "Clear") }
                    } else if (!vm.busy) {
                        TextButton(onClick = { pasteFromClipboard(ctx)?.let { vm.link = it } }) { Text("Paste") }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = vm::transcribeLink,
                    enabled = !vm.busy && vm.link.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Transcribe") }
                OutlinedButton(onClick = openPicker, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                    Text("Choose file")
                }
            }
        }
    }
}

@Composable
private fun WorkingCard(s: UiState.Working, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.stage, style = MaterialTheme.typography.titleSmall)
            if (s.fraction != null) LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Keep the app open until it's done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, openPicker: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = openPicker) { Text("Try a downloaded file instead") }
        }
    }
}

@Composable
private fun TranscriptHeader(
    t: Transcript,
    showTimes: Boolean,
    onToggleTimes: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "${t.wordCount} words · about ${maxOf(1, t.wordCount / 230)} min read",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = onCopy) { Text("Copy") }
            FilledTonalButton(onClick = onSave) { Text("Save") }
            FilledTonalIconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showTimes, onCheckedChange = onToggleTimes)
            Spacer(Modifier.width(10.dp))
            Text("Show timestamps", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ParagraphView(p: Paragraph, showTime: Boolean) {
    Column {
        if (showTime) {
            Text(
                TranscriptFormatter.stamp(p.start),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
        }
        SelectionContainer {
            Text(p.text, fontSize = 18.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SettingsDialog(settings: Settings, onClose: () -> Unit) {
    var provider by remember { mutableStateOf(settings.provider) }
    val keys = remember { Provider.entries.associateWith { settings.keyFor(it) }.toMutableMap() }
    var key by remember { mutableStateOf(keys[provider].orEmpty()) }
    var language by remember { mutableStateOf(settings.language) }
    val uri = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Transcription service", style = MaterialTheme.typography.titleSmall)
                Provider.entries.forEach { pr ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = provider == pr, role = Role.RadioButton,
                            onClick = { keys[provider] = key; provider = pr; key = keys[pr].orEmpty() }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = provider == pr, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(pr.label)
                    }
                }
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { uri.openUri(provider.keyUrl) }) { Text("Get a key") }
                OutlinedTextField(
                    value = language, onValueChange = { language = it.take(3) },
                    label = { Text("Language (optional)") },
                    supportingText = { Text("Two-letter code, e.g. en. Leave blank to detect.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                keys[provider] = key
                keys.forEach { (pr, k) -> settings.setKeyFor(pr, k) }
                settings.provider = provider
                settings.language = language
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ small helpers

private fun pasteFromClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share transcript"))
}

private fun safeFileName(s: String) =
    s.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "transcript" }


// ------------------------------------------------------------------ podcasts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Mode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size)
            ) { Text(if (m == Mode.LINK) "Video link" else "Podcasts") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.podcastBrowser(vm: MainViewModel) {
    val feed = vm.openFeed
    if (feed == null) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = vm.podcastQuery,
                        onValueChange = { vm.podcastQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search podcasts") },
                        placeholder = { Text("Name, or paste an RSS feed link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchPodcasts() })
                    )
                    Button(
                        onClick = vm::searchPodcasts,
                        enabled = vm.podcastQuery.isNotBlank() && !vm.podcastLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Search") }
                }
            }
        }
    } else {
        item {
            Column {
                TextButton(onClick = vm::closeFeed) { Text("← All results") }
                Text(feed.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${feed.episodes.size} episodes · tap one to transcribe",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (vm.podcastLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    vm.podcastError?.let { msg ->
        item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }

    if (feed == null) {
        items(vm.podcastResults) { p ->
            ListItem(
                headlineContent = { Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(p.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { vm.openPodcastFeed(p.feedUrl) }
            )
        }
    } else {
        items(feed.episodes) { ep ->
            ListItem(
                headlineContent = { Text(ep.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(listOf(ep.date, PodcastRepo.prettyDuration(ep.durationSec))
                        .filter { it.isNotBlank() }.joinToString(" · "))
                },
                trailingContent = { Text("Transcribe", color = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { vm.transcribeEpisode(feed, ep) }
            )
        }
    }
}
