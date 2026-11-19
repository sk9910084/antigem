package ai.antigem

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.updater.PluginUpdateCheckWorker
import ai.antigem.data.updater.UpdateCheckWorker
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AntiGemApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(ai.antigem.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { ai.antigem.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        // a crash opens the crash screen (with the report) instead of the app just vanishing
        ai.antigem.crash.CrashHandler.install(this)
        // the crash screen's own process needs none of the startup below
        if (ai.antigem.crash.CrashHandler.isCrashProcess(this)) return
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        ai.antigem.data.preferences.AuthPreferences(this)
        // the chosen light / dark themes and the custom ones, before the first screen draws
        ai.antigem.theme.ThemeManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // mini app reminders and exact-time tasks: alarms again (Android may have dropped them), off the main thread
        ai.antigem.data.miniapps.MiniAppScheduler.rearmAsync(this)
        Thread { ai.antigem.ui.miniapps.MiniAppShortcuts.migrate(this) }.start()
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        ai.antigem.data.ssh.SshSessionFactory.init(this)
        // Connect to PC: the paired PCs and the one in use (AuthPreferences reads the latter on its own too)
        ai.antigem.data.pc.PcConnect.init(this)
        // the cloud project new Antigravity chats start in (Remote Agents), before the first chat is created
        ai.antigem.data.agent.agy.AgyCloud.init(this)
        // Short's build: no analytics at all — installs/updates are not reported anywhere.
        // (Upstream reported them to the original author's TelemetryDeck account.)
    }

    private fun isOfficialRelease(): Boolean = runCatching {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().any { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) } == RELEASE_CERT_SHA256
        }
    }.getOrDefault(false)

    private companion object {
        // SHA-256 of Short's release.jks certificate (alias "antigem"). Public, safe to commit.
        const val RELEASE_CERT_SHA256 = "8A92944C67691FAB76B274C86365B8985278B90BDBD6A87FBCD7A598DB68B428"
    }
}
