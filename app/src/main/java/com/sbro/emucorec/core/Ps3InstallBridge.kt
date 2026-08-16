package com.sbro.emucorec.core

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import net.rpcsx.NativeProgress
import net.rpcsx.ProgressRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class NativeInstallProgress(
    val stage: String,
    val progress: Float,
    val current: Float,
    val total: Float,
    val detail: String?,
)

object Ps3InstallBridge {
    fun interface Listener { fun onProgress(progress: NativeInstallProgress) }

    @Volatile private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    suspend fun installFirmware(context: Context, firmwarePath: String): String? {
        emit("firmware", 0f, "Installing PS3 system software")
        val success = awaitNativeInstall("firmware", 0, 1, "Installing PS3 system software") { progressId ->
            Ps3Runtime.installFirmware(context, firmwarePath, progressId)
        }
        Ps3Runtime.stop()
        emit("firmware", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        return if (success) net.rpcsx.FirmwareRepository.version.value ?: "installed" else null
    }

    private suspend fun installPackage(context: Context, contentPath: String): Int {
        emit("content", 0f, "Installing PS3 PKG or disc image")
        val success = awaitNativeInstall("content", 0, 1, File(contentPath).name) { progressId ->
            Ps3Runtime.installPackage(context, contentPath, progressId)
        }
        if (success) NativeLib.refreshAppsList()
        Ps3Runtime.stop()
        emit("content", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        return if (success) 1 else 0
    }

    suspend fun installLicense(context: Context, licensePath: String): Boolean {
        emit("license", 0f, "Installing PS3 RAP license")
        val file = File(licensePath)
        val success = if (file.extension.equals("rap", true)) {
            Ps3Runtime.installRap(context, licensePath)
        } else {
            awaitNativeInstall("license", 0, 1, file.name) { progressId ->
                Ps3Runtime.installLicense(context, licensePath, "", progressId)
            }
        }
        emit("license", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        return success
    }

    suspend fun installPkg(context: Context, pkgPath: String): Boolean =
        installPackage(context, pkgPath) > 0

    suspend fun installContent(context: Context, paths: List<String>): Boolean {
        val files = paths.map(::File).filter(File::isFile).sortedWith(ArchiveContentInstaller.naturalFileOrder)
        val licences = files.filter { it.extension.equals("rap", true) || it.extension.equals("edat", true) }
        val payloads = files - licences.toSet()
        if (payloads.isEmpty() && licences.isEmpty()) return false

        val splitPackage = payloads.size > 1 &&
            payloads.map(::splitBase).distinct().size == 1 &&
            payloads.all(::isPackagePart)
        val payloadUnits = if (splitPackage) 1 else payloads.size
        val totalUnits = payloadUnits + licences.size
        var unitIndex = 0
        var success = true
        if (splitPackage) {
            emit("content", 0f, "Installing split PS3 package")
            success = awaitNativeInstall("content", unitIndex, totalUnits, "Installing split PS3 package") { progressId ->
                Ps3Runtime.installSplitPackages(context, payloads.map(File::getAbsolutePath), progressId)
            }
            unitIndex++
        } else {
            payloads.forEach { file ->
                if (!success) return@forEach
                success = awaitNativeInstall("content", unitIndex, totalUnits, file.name) { progressId ->
                    Ps3Runtime.installPackage(context, file.absolutePath, progressId)
                }
                unitIndex++
            }
        }
        licences.forEach { file ->
            if (!success) return@forEach
            success = if (file.extension.equals("rap", true)) {
                emitUnit("license", unitIndex, totalUnits, 0f, file.name)
                // The core only finds a RAP by its content id (from the game's
                // NPDRM header), so name the license from the DLC package's
                // PARAM.SFO instead of trusting the user's filename.
                val pkgContentId = if (!splitPackage) {
                    payloads.firstOrNull()?.let { payload ->
                        runCatching { Ps3Runtime.pkgContentId(context, payload.absolutePath) }
                            .getOrNull()?.ifBlank { null }
                    }
                } else {
                    null
                }
                Ps3Runtime.installRap(context, file.absolutePath, pkgContentId).also { installed ->
                    emitUnit("license", unitIndex, totalUnits, if (installed) 1f else 0f, file.name)
                }
            } else {
                awaitNativeInstall("license", unitIndex, totalUnits, file.name) { progressId ->
                    Ps3Runtime.installLicense(context, file.absolutePath, "", progressId)
                }
            }
            unitIndex++
        }
        if (success) NativeLib.refreshAppsList()
        Ps3Runtime.stop()
        emit("content", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        return success
    }

    private fun isPackagePart(file: File): Boolean =
        file.name.contains(".pkg", ignoreCase = true)

    // Splits ship either as "Game.pkg" + "Game.pkg.1" + "Game.pkg.2" or as
    // "Game.pkg.0" + "Game.pkg.1" + ...: every part shares the base name
    // without the ".pkg" (and optional numeric suffix) extension.
    private fun splitBase(file: File): String {
        val lower = file.name.lowercase()
        return lower.replace(Regex("\\.pkg\\.\\d+$"), "").removeSuffix(".pkg")
    }

    private suspend fun awaitNativeInstall(
        stage: String,
        unitIndex: Int,
        totalUnits: Int,
        fallbackDetail: String,
        start: (Long) -> Boolean,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        val progressId = ProgressRepository.create { progress ->
            emitNative(stage, unitIndex, totalUnits, fallbackDetail, progress)
            if ((progress.failed || progress.completed) && settled.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(!progress.failed)
            }
        }
        continuation.invokeOnCancellation {
            if (settled.compareAndSet(false, true)) ProgressRepository.cancel(progressId)
        }
        val accepted = runCatching { start(progressId) }.getOrDefault(false)
        if (!settled.get()) {
            if (accepted) {
                ProgressRepository.onProgressEvent(progressId, 1, 1, null)
            } else {
                ProgressRepository.onProgressEvent(progressId, -1, 0, "Installation failed")
            }
        }
    }

    private fun emitNative(
        stage: String,
        unitIndex: Int,
        totalUnits: Int,
        fallbackDetail: String,
        progress: NativeProgress,
    ) {
        val unitProgress = if (progress.maximum > 0L) {
            progress.value.toFloat().div(progress.maximum.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val overall = (unitIndex + unitProgress).div(totalUnits.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        listener?.onProgress(
            NativeInstallProgress(
                stage = stage,
                progress = overall,
                current = progress.value.toFloat(),
                total = progress.maximum.toFloat(),
                detail = progress.message?.takeIf(String::isNotBlank) ?: fallbackDetail,
            )
        )
    }

    private fun emitUnit(stage: String, unitIndex: Int, totalUnits: Int, value: Float, detail: String) {
        val overall = (unitIndex + value.coerceIn(0f, 1f)).div(totalUnits.coerceAtLeast(1).toFloat())
        listener?.onProgress(NativeInstallProgress(stage, overall, value, 1f, detail))
    }

    private fun emit(stage: String, progress: Float, detail: String) {
        listener?.onProgress(NativeInstallProgress(stage, progress, progress, 1f, detail))
    }
}
