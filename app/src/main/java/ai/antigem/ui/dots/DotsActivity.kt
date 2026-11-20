package ai.antigem.ui.dots

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ai.antigem.data.dots.DotsManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.GeminiTheme
import ai.antigem.theme.isSystemInDarkThemeRobust
import kotlinx.coroutines.launch

/**
 * Dots: the OpenDots server running on this phone, in a WebView.
 *
 * First run downloads the server bundle (~150 MB, one time), installs the Ubuntu container and
 * Node.js 24 if they are missing, then starts the server on http://127.0.0.1:4310.
 * Chats need a CopilotKit Intelligence key (made on a computer: `npx copilotkit login`, then pick a
 * project) and an OpenAI-compatible key; the model endpoint defaults to Xkiro (free).
 */
class DotsActivity : ComponentActivity() {
    private val prefs by lazy { AuthPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val initial = remember { prefs.getThemeModeSync() }
            val mode by prefs.themeMode.collectAsState(initial = initial)
            val systemDark = isSystemInDarkThemeRobust()
            GeminiTheme(darkTheme = when (mode) { "DARK" -> true; "LIGHT" -> false; else -> systemDark }) {
                DotsScreen(onBack = { finish() })
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DotsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by DotsManager.status.collectAsState()
    val log by DotsManager.log.collectAsState()
    val intelligenceKeyState by DotsManager.intelligenceKeyFlow(context).collectAsState(initial = null)
    val openAiKeyState by DotsManager.openAiKeyFlow(context).collectAsState(initial = null)
    val baseUrlState by DotsManager.baseUrlFlow(context).collectAsState(initial = null)
    val modelState by DotsManager.modelFlow(context).collectAsState(initial = null)
    // the fields must not save until the stored keys have actually loaded: saving the
    // initial empty values would wipe a key that is already stored
    val keysLoaded = intelligenceKeyState != null && openAiKeyState != null &&
        baseUrlState != null && modelState != null
    val intelligenceKey = intelligenceKeyState ?: ""
    val openAiKey = openAiKeyState ?: ""
    val baseUrl = baseUrlState ?: DotsManager.DEFAULT_BASE_URL
    val model = modelState ?: DotsManager.DEFAULT_MODEL

    var editIntelligence by remember { mutableStateOf<String?>(null) }
    var editOpenAi by remember { mutableStateOf<String?>(null) }
    var editBaseUrl by remember { mutableStateOf<String?>(null) }
    var editModel by remember { mutableStateOf<String?>(null) }
    var showWeb by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val running = status is DotsManager.DotsStatus.Running

    // if the server is already up (started earlier), go straight to the web UI
    LaunchedEffect(Unit) {
        if (DotsManager.isRunning()) showWeb = true
    }
    LaunchedEffect(running) {
        if (running) showWeb = true
    }
    BackHandler(enabled = showWeb) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dots") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (running) {
                        IconButton(onClick = { DotsManager.stop(); showWeb = false }) {
                            Icon(Icons.Outlined.Stop, "Stop the Dots server")
                        }
                    }
                    IconButton(onClick = { showWeb = false }) {
                        Icon(Icons.Outlined.Refresh, "Show setup")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
    ) { padding ->
        if (showWeb && running) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.setSupportMultipleWindows(false)
                        webViewClient = WebViewClient()
                        loadUrl(DotsManager.serverUrl())
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "OpenDots runs on this phone: persistent AI agents (Dots), each with its own computer. " +
                    "First run downloads the server (~150 MB, one time) and installs the Ubuntu container and Node.js 24 if they are missing.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Keys", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = editIntelligence ?: intelligenceKey,
                        onValueChange = { editIntelligence = it },
                        label = { Text("CopilotKit Intelligence key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Made on a computer: run `npx copilotkit login`, pick a project, and paste the key here. " +
                            "Without it Dots opens in setup state and cannot chat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = editOpenAi ?: openAiKey,
                        onValueChange = { editOpenAi = it },
                        label = { Text("OpenAI-compatible key (Xkiro)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editBaseUrl ?: baseUrl,
                        onValueChange = { editBaseUrl = it },
                        label = { Text("Model endpoint") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editModel ?: model,
                        onValueChange = { editModel = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        scope.launch {
                            DotsManager.saveKeys(
                                context,
                                editIntelligence ?: intelligenceKey,
                                editOpenAi ?: openAiKey,
                                editBaseUrl ?: baseUrl,
                                editModel ?: model
                            )
                            editIntelligence = null
                            editOpenAi = null
                            editBaseUrl = null
                            editModel = null
                            Toast.makeText(context, "Keys saved.", Toast.LENGTH_SHORT).show()
                        }
                    }, enabled = keysLoaded) { Text("Save keys") }
                }
            }

            val statusText = when (val s = status) {
                is DotsManager.DotsStatus.Idle -> "Idle."
                is DotsManager.DotsStatus.Downloading -> "Downloading the server bundle… " +
                    "${s.downloadedBytes / 1_048_576} MB" +
                    (if (s.totalBytes > 0) " of ${s.totalBytes / 1_048_576} MB" else "")
                is DotsManager.DotsStatus.Extracting -> "Extracting the server bundle…"
                is DotsManager.DotsStatus.Installing -> "Installing: ${s.step}"
                is DotsManager.DotsStatus.Starting -> "Starting the Dots server…"
                is DotsManager.DotsStatus.Running -> "Running on ${DotsManager.serverUrl()}"
                is DotsManager.DotsStatus.Stopped -> "Stopped."
                is DotsManager.DotsStatus.Error -> "Error: ${s.message}"
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    if (log.isNotEmpty()) {
                        Text(
                            log.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    val busy = status is DotsManager.DotsStatus.Downloading ||
                        status is DotsManager.DotsStatus.Extracting ||
                        status is DotsManager.DotsStatus.Installing ||
                        status is DotsManager.DotsStatus.Starting
                    Button(onClick = {
                        scope.launch {
                            DotsManager.saveKeys(
                                context,
                                editIntelligence ?: intelligenceKey,
                                editOpenAi ?: openAiKey,
                                editBaseUrl ?: baseUrl,
                                editModel ?: model
                            )
                            DotsManager.start(context)
                        }
                    }, enabled = keysLoaded && !busy) { Text(if (running) "Restart Dots" else "Start Dots") }
                }
            }

            Text(
                "Note: the Playwright browser/computer service is not included — it cannot run on a phone. " +
                    "Dots browse through their connected apps and MCP tools instead.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
