package ai.antigem.data.dots

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalPtySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Dots: the OpenDots server (CopilotKit/OpenDots v0.1.0, MIT) running inside AntiGem.
 *
 * The server is a Node 24 app; it runs in the same proot-distro Ubuntu container REA and Android Dev
 * use, and the Dots UI is the server's own web app in a WebView at http://127.0.0.1:4310.
 * First run downloads the server bundle (~150 MB, one time) from this repo's releases, installs the
 * Ubuntu container and Node.js 24 if they are missing, then starts the server.
 *
 * Chats need a CopilotKit Intelligence key (made on a computer: `npx copilotkit login`, then a project
 * select) and an OpenAI-compatible key. The model endpoint defaults to Xkiro (free), the key is the
 * user's own. The Playwright browser/computer service is NOT bundled: it cannot run on the phone.
 */
object DotsManager {
    private const val TAG = "DotsManager"

    const val PORT = 4310
    const val BUNDLE_URL =
        "https://github.com/sk9910084/antigem/releases/download/opendots-server-v0.1.0/opendots-server.tgz"
    private const val BUNDLE_SHA256 =
        "51fe20bf76b95d7a4721fc81810a98d0bbba099a705053c877df9a0ebe8b3624"
    private const val BUNDLE_FILE = "opendots-server.tgz"
    private const val CONTAINER = "ubuntu"
    private const val NVM_VERSION = "v0.40.3"

    const val DEFAULT_BASE_URL = "https://api.xkiro.com/v1"
    const val DEFAULT_MODEL = "mistral-small-2603"

    private val Context.dotsDataStore by preferencesDataStore(name = "dots_prefs")
    private val KEY_INTELLIGENCE = stringPreferencesKey("intelligence_api_key")
    private val KEY_OPENAI = stringPreferencesKey("openai_api_key")
    private val KEY_BASE_URL = stringPreferencesKey("openai_base_url")
    private val KEY_MODEL = stringPreferencesKey("openai_model")

    fun intelligenceKeyFlow(context: Context) =
        context.dotsDataStore.data.map { it[KEY_INTELLIGENCE].orEmpty() }

    fun openAiKeyFlow(context: Context) =
        context.dotsDataStore.data.map { it[KEY_OPENAI].orEmpty() }

    fun baseUrlFlow(context: Context) =
        context.dotsDataStore.data.map { it[KEY_BASE_URL] ?: DEFAULT_BASE_URL }

    fun modelFlow(context: Context) =
        context.dotsDataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }

    suspend fun saveKeys(
        context: Context,
        intelligenceKey: String,
        openAiKey: String,
        baseUrl: String,
        model: String,
    ) {
        context.dotsDataStore.edit { prefs ->
            prefs[KEY_INTELLIGENCE] = intelligenceKey.trim()
            prefs[KEY_OPENAI] = openAiKey.trim()
            prefs[KEY_BASE_URL] = baseUrl.trim().ifBlank { DEFAULT_BASE_URL }
            prefs[KEY_MODEL] = model.trim().ifBlank { DEFAULT_MODEL }
        }
    }

    sealed class DotsStatus {
        object Idle : DotsStatus()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DotsStatus()
        object Extracting : DotsStatus()
        data class Installing(val step: String) : DotsStatus()
        object Starting : DotsStatus()
        data class Running(val sinceMs: Long = System.currentTimeMillis()) : DotsStatus()
        object Stopped : DotsStatus()
        data class Error(val message: String) : DotsStatus()
    }

    private val _status = MutableStateFlow<DotsStatus>(DotsStatus.Idle)
    val status: StateFlow<DotsStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startJob: Job? = null
    private var serverSession: LocalPtySession? = null
    private var appContext: Context? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun serverUrl() = "http://127.0.0.1:$PORT/"

    private fun homeDir(context: Context) = LocalEnvironmentManager.getHomeDir(context)
    private fun prefixDir(context: Context) = LocalEnvironmentManager.getPrefixDir(context)
    private fun rootfsDir(context: Context) =
        File(prefixDir(context), "var/lib/proot-distro/containers/$CONTAINER/rootfs")

    private fun dotsDir(context: Context) = File(homeDir(context), "opendots")
    private fun serverEntry(context: Context) = File(dotsDir(context), "dist/server/server/index.js")
    private fun nodeBin(context: Context) = File(rootfsDir(context), "root/tools/node/bin/node")

    fun isInstalled(context: Context) = serverEntry(context).exists()
    fun isContainerInstalled(context: Context) =
        File(rootfsDir(context), "usr/bin/apt-get").exists()

    fun isNodeInstalled(context: Context) = nodeBin(context).exists()

    suspend fun isRunning(): Boolean = healthCheck()

    private fun healthCheck(): Boolean = try {
        http.newCall(Request.Builder().url(serverUrl()).build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        _log.value = (_log.value + line).takeLast(200)
    }

    fun start(context: Context) {
        // one start at a time: a second tap while the first is still working only ever
        // made a second server race the first for port 4310
        val current = _status.value
        if (current is DotsStatus.Downloading || current is DotsStatus.Extracting ||
            current is DotsStatus.Installing || current is DotsStatus.Starting
        ) {
            appendLog("Dots is already starting — please wait.")
            return
        }
        startJob?.cancel()
        startJob = scope.launch { startInternal(context.applicationContext) }
    }

    fun stop() {
        startJob?.cancel()
        scope.launch { stopInternal() }
    }

    private suspend fun stopInternal() {
        val session = serverSession
        _status.value = DotsStatus.Stopped
        try {
            session?.write("\u0003")
        } catch (_: Exception) {
        }
        // best effort: kill the node server inside the container, then close the session
        val ctx = appContext
        if (ctx != null) {
            try {
                LocalEnvironmentManager.executeCommand(
                    "proot-distro login $CONTAINER -- /usr/bin/pkill -f 'dist/server/server/index.js' || true",
                    ctx, timeoutSeconds = 30
                )
            } catch (_: Exception) {
            }
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        serverSession = null
        appendLog("Dots stopped.")
    }

    private suspend fun startInternal(context: Context) {
        appContext = context.applicationContext
        if (healthCheck()) {
            appendLog("Dots server is already running.")
            _status.value = DotsStatus.Running()
            return
        }
        if (!LocalEnvironmentManager.isInstalled(context)) {
            _status.value = DotsStatus.Error(
                "The Linux environment is not installed yet. Install it first (Settings → Linux environment), then start Dots."
            )
            return
        }
        try {
            if (!isInstalled(context)) {
                downloadBundle(context)
                extractBundle(context)
            }
            if (!isContainerInstalled(context)) {
                _status.value = DotsStatus.Installing("Ubuntu container (about 150 MB, one time)…")
                appendLog("Installing the Ubuntu container…")
                val installed = runSetupStep(
                    context,
                    "command -v proot-distro >/dev/null 2>&1 || (apt-get update -q && apt-get install -y -q proot-distro); proot-distro install ubuntu:24.04",
                    timeoutSeconds = 900
                )
                if (!installed || !isContainerInstalled(context)) {
                    _status.value = DotsStatus.Error("The Ubuntu container could not be installed. Check the log and try again.")
                    return
                }
                appendLog("Ubuntu container installed.")
            }
            if (File(rootfsDir(context), "usr/bin/curl").exists().not()) {
                _status.value = DotsStatus.Installing("Base tools in the container (curl, git)…")
                appendLog("Installing base tools in the container…")
                val ok = runSetupStep(
                    context,
                    containerCommand(
                        context,
                        "dpkg --configure -a >/dev/null 2>&1; apt-get update -q && apt-get install -y -q --no-install-recommends ca-certificates curl xz-utils procps file unzip python3 git"
                    ),
                    timeoutSeconds = 900
                )
                if (!ok) {
                    _status.value = DotsStatus.Error("Base tools could not be installed in the container. Check the log and try again.")
                    return
                }
            }
            if (!isNodeInstalled(context)) {
                _status.value = DotsStatus.Installing("Node.js 24 (one time)…")
                appendLog("Installing Node.js 24 with nvm…")
                val ok = runSetupStep(context, containerCommand(context, nodeInstallScript()), timeoutSeconds = 900)
                if (!ok || !isNodeInstalled(context)) {
                    _status.value = DotsStatus.Error("Node.js 24 could not be installed. Check the log and try again.")
                    return
                }
                appendLog("Node.js installed.")
            }
            writeEnvFile(context)
            // a previous attempt may have left a session behind: stop it first so two
            // servers never race for port 4310
            val old = serverSession
            if (old != null && !old.isExited.value) {
                stopInternal()
            }
            serverSession = null
            _status.value = DotsStatus.Starting
            appendLog("Starting the Dots server…")
            val command = serverCommand(context)
            val session = withContext(Dispatchers.Main) {
                LocalPtySession(
                    id = "dots-server-pty",
                    initialTitle = "Dots Server",
                    context = context,
                    isSsh = false,
                    initialCommand = command,
                    initialCols = 80,
                    initialRows = 24
                )
            }
            serverSession = session
            // wait until the server answers, up to 60 s
            var up = false
            var exitedEarly = false
            for (i in 0 until 60) {
                delay(1000)
                if (healthCheck()) {
                    up = true
                    break
                }
                if (session.isExited.value) {
                    exitedEarly = true
                    break
                }
            }
            if (up) {
                appendLog("Dots server is running on ${serverUrl()}")
                _status.value = DotsStatus.Running()
            } else {
                if (exitedEarly) appendLog("The server process exited right away. Running a quick check to see why…")
                diagnoseFailure(context)
                _status.value = DotsStatus.Error("The Dots server did not answer on port $PORT. The check above shows why — send a screenshot of the log.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Dots start failed", e)
            _status.value = DotsStatus.Error(e.message ?: "Start failed.")
        }
    }

    /**
     * The command that starts the server inside the container. The server reads its settings
     * from process.env, so the .env file is sourced in bash instead of Node's
     * --env-file-if-exists flag: older Node versions do not know that flag and exit at once
     * ("bad option"), which looks exactly like this silent start failure.
     */
    private fun serverCommand(context: Context): String {
        val home = homeDir(context).absolutePath
        return containerCommand(
            context,
            "cd $home/opendots && set -a && [ -f .env ] && . ./.env; set +a; exec /root/tools/node/bin/node dist/server/server/index.js"
        )
    }

    /**
     * Runs the server once in the foreground (25 s) and streams what it prints into the log,
     * so a start failure shows its real cause (bad flag, missing file, port busy) instead of
     * only "did not answer". The run is killed after 25 s; it never stays behind.
     */
    private suspend fun diagnoseFailure(context: Context) {
        appendLog("Check: starting the server once to see what it prints…")
        try {
            LocalEnvironmentManager.executeCommand(
                serverCommand(context), context, timeoutSeconds = 25,
                customEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                onOutputLine = { line -> if (line.isNotBlank()) appendLog(line.trimEnd()) }
            )
        } catch (e: Exception) {
            appendLog("Check failed to run: ${e.message}")
        }
    }

    /** Runs a one-shot setup command in the Termux shell; false on a non-zero exit. */
    private suspend fun runSetupStep(context: Context, command: String, timeoutSeconds: Long): Boolean {
        var ok = false
        val result = LocalEnvironmentManager.executeCommand(
            command, context, timeoutSeconds = timeoutSeconds,
            customEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
            onOutputLine = { line -> if (line.isNotBlank()) appendLog(line.trimEnd()) }
        )
        ok = result.exitCode == 0
        return ok
    }

    /** Wraps a command to run inside the Ubuntu container (same shape as rea-setup.sh `inside`). */
    private fun containerCommand(context: Context, inner: String): String {
        val home = homeDir(context).absolutePath
        return "proot-distro login $CONTAINER --shared-tmp -b \"$home:$home\" -- " +
            "/usr/bin/env -i HOME=/root TERM=dumb LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive " +
            "\"PATH=/root/tools/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\" " +
            "/bin/bash -c '$inner'"
    }

    /** Node 24 via nvm, symlinked to /root/tools/node — the same install REA uses. */
    private fun nodeInstallScript(): String = listOf(
        "set -e",
        "export NVM_DIR=/root/.nvm",
        "if [ ! -s \"\$NVM_DIR/nvm.sh\" ]; then curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/$NVM_VERSION/install.sh | bash; fi",
        ". \"\$NVM_DIR/nvm.sh\"",
        "nvm install 24 --no-progress",
        "nvm alias default 24 >/dev/null",
        "mkdir -p /root/tools",
        "ln -sfnr \"\$(dirname \"\$(dirname \"\$(nvm which 24)\")\")\" /root/tools/node",
        "echo \"node \$(/root/tools/node/bin/node -v)\""
    ).joinToString("; ")

    private suspend fun downloadBundle(context: Context) {
        val target = File(homeDir(context), BUNDLE_FILE)
        appendLog("Downloading the Dots server bundle (~150 MB, one time)…")
        _status.value = DotsStatus.Downloading(0, 0)
        val request = Request.Builder()
            .url(BUNDLE_URL)
            .header("User-Agent", "AntiGem-Dots/1.0")
            .build()
        downloadHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Download failed: empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            FileOutputStream(target).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        _status.value = DotsStatus.Downloading(read, total)
                    }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != BUNDLE_SHA256) {
            target.delete()
            throw IllegalStateException("Download failed: checksum mismatch (the file may be incomplete). Try again.")
        }
        appendLog("Bundle downloaded and verified.")
    }

    private suspend fun extractBundle(context: Context) {
        _status.value = DotsStatus.Extracting
        appendLog("Extracting the server bundle…")
        val home = homeDir(context).absolutePath
        val ok = runSetupStep(
            context,
            containerCommand(context, "mkdir -p $home/opendots && tar -xzf $home/$BUNDLE_FILE -C $home && rm -f $home/$BUNDLE_FILE"),
            timeoutSeconds = 600
        )
        if (!ok || !isInstalled(context)) {
            throw IllegalStateException("The server bundle could not be extracted. Check the log and try again.")
        }
        appendLog("Server bundle extracted.")
    }

    private suspend fun writeEnvFile(context: Context) {
        val prefs = context.dotsDataStore.data.first()
        val lines = StringBuilder()
        lines.appendLine("HOST=127.0.0.1")
        lines.appendLine("PORT=$PORT")
        lines.appendLine("DATABASE_PATH=data/opendots.sqlite")
        lines.appendLine("OWNER_ID=opendots-owner")
        lines.appendLine("DO_NOT_TRACK=1")
        lines.appendLine("COPILOTKIT_TELEMETRY_DISABLED=true")
        prefs[KEY_INTELLIGENCE]?.takeIf { it.isNotBlank() }?.let { lines.appendLine("INTELLIGENCE_API_KEY=${shellQuote(it)}") }
        prefs[KEY_OPENAI]?.takeIf { it.isNotBlank() }?.let { lines.appendLine("OPENAI_API_KEY=${shellQuote(it)}") }
        lines.appendLine("OPENAI_BASE_URL=${shellQuote(prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL)}")
        lines.appendLine("OPENAI_MODEL=${shellQuote(prefs[KEY_MODEL] ?: DEFAULT_MODEL)}")
        val envFile = File(dotsDir(context), ".env")
        envFile.parentFile?.mkdirs()
        envFile.writeText(lines.toString())
    }

    /** Single-quoted for bash sourcing: the .env file is sourced, not read by Node's --env-file. */
    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
