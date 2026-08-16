package com.sbro.emucorec.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import net.rpcsx.BootResult
import net.rpcsx.EmulatorState
import net.rpcsx.FirmwareRepository
import net.rpcsx.GameRepository
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX
import com.sbro.emucorec.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object Ps3Runtime {
    private const val TAG = "Ps3Runtime"
    private const val USER_ID = "00000001"
    private const val SURFACE_CREATED = 0
    private const val SURFACE_CHANGED = 1
    private const val SURFACE_DESTROYED = 2

    @Volatile private var mainPumpThread: Thread? = null
    @Volatile private var compilePumpThread: Thread? = null
    @Volatile private var currentSurface: Surface? = null

    fun ensureInitialized(context: Context): Boolean = synchronized(this) {
        if (RPCSX.initialized) {
            ensurePumpsRunning()
            return true
        }

        val appContext = context.applicationContext
        EmulatorStorage.prepareRuntime(appContext)
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val core = File(nativeDir, "libemucorec-core.so")
        if (!core.isFile) {
            Log.e(TAG, "RPCS3 core is missing: ${core.absolutePath}")
            return false
        }

        RPCSX.nativeLibDirectory = nativeDir
        if (RPCSX.activeLibrary.value == null && !RPCSX.openLibrary(core.absolutePath)) {
            Log.e(TAG, "Could not load RPCS3 core: ${core.absolutePath}")
            return false
        }

        // Feed the core the identity strings shown in the performance overlay header
        // (app version | build | core version, plus the device name).
        runCatching {
            RPCSX.instance.setAppInfo(
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE.toString(),
                MobileSocNameMapper.currentDeviceName(),
            )
        }

        val root = EmulatorStorage.ps3Root(appContext).absolutePath.trimEnd('/') + "/"
        RPCSX.rootDirectory = root
        File(root, ".nomedia").runCatching { parentFile?.mkdirs(); createNewFile() }

        if (!RPCSX.instance.initialize(root, USER_ID)) {
            Log.e(TAG, "RPCS3 initialization failed for $root")
            return false
        }

        RPCSX.initialized = true
        runCatching { FirmwareRepository.load() }
        ensurePumpsRunning()
        true
    }

    fun ensurePumpsRunning() {
        synchronized(this) {
            if (!RPCSX.initialized) return
            if (mainPumpThread?.isAlive != true) {
                mainPumpThread = Thread({
                    runCatching { RPCSX.instance.startMainThreadProcessor() }
                }, "ps3-main-pump").apply {
                    isDaemon = true
                    start()
                }
            }
            if (compilePumpThread?.isAlive != true) {
                compilePumpThread = Thread({
                    runCatching { RPCSX.instance.processCompilationQueue() }
                }, "ps3-compile-pump").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun installFirmware(context: Context, uri: Uri): Boolean {
        if (!ensureInitialized(context)) return false
        val progressId = ProgressRepository.create()
        return (context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            RPCSX.instance.installFw(descriptor.fd, progressId)
        } ?: false).also { accepted -> rejectProgressIfNeeded(progressId, accepted) }
    }

    fun installFirmware(context: Context, source: String): Boolean {
        val progressId = ProgressRepository.create()
        return installFirmware(context, source, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installFirmware(context: Context, source: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            RPCSX.instance.installFw(descriptor.fd, progressId)
        } ?: false

    fun installPackage(context: Context, uri: Uri): Boolean {
        if (!ensureInitialized(context)) return false
        val progressId = ProgressRepository.create()
        GameRepository.createGameInstallEntry(progressId)
        return (context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            RPCSX.instance.install(descriptor.fd, progressId)
        } ?: false).also { accepted -> rejectProgressIfNeeded(progressId, accepted) }
    }

    fun installPackage(context: Context, source: String): Boolean {
        val progressId = ProgressRepository.create()
        return installPackage(context, source, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installPackage(context: Context, source: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            GameRepository.createGameInstallEntry(progressId)
            RPCSX.instance.install(descriptor.fd, progressId)
        } ?: false

    fun installSplitPackages(context: Context, sources: List<String>): Boolean {
        val progressId = ProgressRepository.create()
        return installSplitPackages(context, sources, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installSplitPackages(context: Context, sources: List<String>, progressId: Long): Boolean {
        if (sources.isEmpty() || !ensureInitialized(context)) return false
        val descriptors = ArrayList<ParcelFileDescriptor>(sources.size)
        return try {
            sources.forEach { source ->
                descriptors += openDescriptor(context, source) ?: return false
            }
            // The official RPCS3 core has no split-pkg API: a split package is
            // just the original .pkg cut into numbered parts, so concatenating
            // them in order reproduces the .pkg that install() accepts.
            val merged = File(context.applicationContext.cacheDir, "split-pkg-merged.pkg")
            try {
                merged.outputStream().use { out ->
                    descriptors.forEach { descriptor ->
                        FileInputStream(descriptor.fileDescriptor).use { input -> input.copyTo(out) }
                    }
                }
                GameRepository.createGameInstallEntry(progressId)
                ParcelFileDescriptor.open(merged, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    RPCSX.instance.install(descriptor.fd, progressId)
                }
            } finally {
                merged.delete()
            }
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    fun installLicense(context: Context, source: String, gamePath: String): Boolean =
        if (File(source).extension.equals("rap", true) && gamePath.isBlank()) {
            installRap(context, source)
        } else {
            val progressId = ProgressRepository.create()
            installLicense(context, source, gamePath, progressId).also { accepted ->
                rejectProgressIfNeeded(progressId, accepted)
            }
        }

    fun installLicense(context: Context, source: String, gamePath: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            RPCSX.instance.installKey(descriptor.fd, progressId, gamePath)
        } ?: false

    /** Thrown when a license file cannot be installed. The message is user-facing. */
    class LicenseInstallException(message: String) : Exception(message)

    /** Reads a PKG's PARAM.SFO content id, used to name its RAP license correctly. */
    fun pkgContentId(context: Context, source: String): String? =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use null
            RPCSX.instance.pkgContentId(descriptor.fd)
        }

    /** Installs a RAP license. When [contentId] is null the core scans the
     *  installed games, verifies the key against each EBOOT and names the
     *  license by its content id automatically. */
    fun installRap(context: Context, source: String, contentId: String? = null): Boolean {
        Log.d(TAG, "installRap: source=$source, contentId=$contentId")
        if (!ensureInitialized(context)) {
            Log.e(TAG, "installRap: ensureInitialized failed")
            return false
        }
        val input = File(source)
        Log.d(TAG, "installRap: input.isFile=${input.isFile}, length=${input.length()}")
        if (!input.isFile || input.length() != 0x10L) {
            throw LicenseInstallException("RAP license must be exactly 16 bytes (invalid or corrupted file)")
        }

        if (contentId.isNullOrBlank()) {
            Log.d(TAG, "installRap: auto-scan mode (no contentId)")
            val failure = AtomicReference<String?>()
            val progressId = ProgressRepository.create { progress ->
                Log.d(TAG, "installRap: progress failed=${progress.failed}, message=${progress.message}")
                if (progress.failed) {
                    failure.set(progress.message?.takeIf(String::isNotBlank)
                        ?: "Failed to install the RAP license")
                }
            }
            val accepted = openDescriptor(context, source)?.use { descriptor ->
                Log.d(TAG, "installRap: fd=${descriptor.fd}, calling installRapAuto")
                RPCSX.instance.installRapAuto(descriptor.fd, progressId)
            } ?: false
            Log.d(TAG, "installRap: installRapAuto returned $accepted")
            ProgressRepository.cancel(progressId)
            if (!accepted) {
                val msg = failure.get() ?: "Failed to install the RAP license"
                Log.e(TAG, "installRap: failed: $msg")
                throw LicenseInstallException(msg)
            }
            // Re-write RAP files using Java to ensure correct UID/permissions
            val user = runCatching { RPCSX.instance.getUser() }.getOrNull().orEmpty().ifBlank { USER_ID }
            val exdataDir = File(RPCSX.getHdd0Dir(), "home/$user/exdata")
            if (exdataDir.isDirectory) {
                exdataDir.listFiles()?.filter { it.extension == "rap" }?.forEach { rapFile ->
                    val dest = File(exdataDir, rapFile.name)
                    input.inputStream().use { src ->
                        dest.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    Log.d(TAG, "installRap: re-wrote ${rapFile.name} via Java (UID=${android.os.Process.myUid()})")
                }
            }
            Log.d(TAG, "installRap: success")
            return true
        }

        val resolvedContentId = contentId.trim()
        if (!resolvedContentId.matches(contentIdPattern)) {
            throw LicenseInstallException(
                "RAP license name must be its content ID (e.g. UP0001-BLUS30423_00-DLC0000000001.rap). " +
                    "Rename the file and try again."
            )
        }

        val user = runCatching { RPCSX.instance.getUser() }.getOrNull().orEmpty().ifBlank { USER_ID }
        val directory = File(RPCSX.getHdd0Dir(), "home/$user/exdata").canonicalFile
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw LicenseInstallException("Failed to create the license directory")
        }
        val destination = File(directory, "$resolvedContentId.rap").canonicalFile
        if (destination.parentFile != directory) {
            throw LicenseInstallException("Invalid license destination path")
        }

        val temporary = File.createTempFile(".emucorec-rap-", ".tmp", directory)
        try {
            input.inputStream().use { sourceStream ->
                temporary.outputStream().use { destinationStream -> sourceStream.copyTo(destinationStream) }
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
            return true
        } finally {
            temporary.delete()
        }
    }

    private val contentIdPattern =
        Regex("^([A-Za-z0-9]{2,6}-)?[A-Za-z0-9]{9}_[0-9]{2}-[A-Za-z0-9-]{1,40}$")

    private fun rejectProgressIfNeeded(progressId: Long, accepted: Boolean) {
        if (!accepted) ProgressRepository.onProgressEvent(progressId, -1, 0, "Installation failed")
    }

    fun boot(context: Context, path: String, titleId: String? = null): BootResult {
        if (!ensureInitialized(context)) return BootResult.GenericError
        ensurePumpsRunning()
        Ps3CoreSettingOverrides.applyForGame(context, titleId)
        return RPCSX.boot(path)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (!RPCSX.initialized) return
        val event = if (currentSurface == null) SURFACE_CREATED else SURFACE_CHANGED
        currentSurface = surface
        RPCSX.instance.surfaceEvent(surface, event)
    }

    fun detachSurface() {
        currentSurface?.let { surface ->
            if (RPCSX.initialized) RPCSX.instance.surfaceEvent(surface, SURFACE_DESTROYED)
        }
        currentSurface = null
    }

    /** No-op: the official RPCS3 core has no pause, only kill/resume.
     *  Kept as a call site so the emulation UI state machine stays unchanged. */
    fun pause() {
    }

    fun resume() {
        if (RPCSX.initialized) runCatching { RPCSX.instance.resume() }
    }

    fun stop() {
        detachSurface()
        if (RPCSX.initialized) runCatching { RPCSX.instance.kill() }
        mainPumpThread = null
        compilePumpThread = null
    }

    fun state(): EmulatorState =
        if (RPCSX.initialized) runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)
        else EmulatorState.Stopped

    private fun openDescriptor(context: Context, source: String): ParcelFileDescriptor? {
        val uri = Uri.parse(source)
        return if (uri.scheme.equals("content", ignoreCase = true)) {
            context.contentResolver.openFileDescriptor(uri, "r")
        } else {
            val path = if (uri.scheme.equals("file", ignoreCase = true)) uri.path else source
            path?.let(::File)?.takeIf(File::isFile)?.let { file ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    private inline fun File.runCatching(block: File.() -> Unit) {
        kotlin.runCatching { block() }
    }
}
