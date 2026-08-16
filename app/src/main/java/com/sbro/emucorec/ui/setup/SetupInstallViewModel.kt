package com.sbro.emucorec.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorec.R
import com.sbro.emucorec.core.DocumentPathResolver
import com.sbro.emucorec.core.ArchiveContentInstaller
import com.sbro.emucorec.core.ArchivePreparationError
import com.sbro.emucorec.core.ArchivePreparationException
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.core.NativeInstallProgress
import com.sbro.emucorec.core.Ps3InstallBridge
import com.sbro.emucorec.core.Ps3Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File

enum class InstallOperation { Firmware, License, Pkg }
enum class InstallStatus { Idle, Running, Success, Error }

data class SetupInstallUiState(
    val status: InstallStatus = InstallStatus.Idle,
    val operation: InstallOperation? = null,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
    val current: Int? = null,
    val total: Int? = null,
    val detail: String? = null,
    val message: String? = null,
) {
    val visible: Boolean get() = status != InstallStatus.Idle
}

class SetupInstallViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>()
    private val _uiState = MutableStateFlow(SetupInstallUiState())
    val uiState: StateFlow<SetupInstallUiState> = _uiState.asStateFlow()
    private val stagedFilesToCleanup = mutableListOf<String>()
    private val temporaryRootsToCleanup = mutableListOf<File>()

    fun dismissDialog() {
        if (_uiState.value.status != InstallStatus.Running) _uiState.value = SetupInstallUiState()
    }

    fun installFirmware(uriString: String) = runInstall(InstallOperation.Firmware) {
        val path = resolveInstallSource(uriString) ?: return@runInstall finishError(
            appContext.getString(R.string.install_dialog_firmware_failed)
        )
        val version = Ps3InstallBridge.installFirmware(appContext, path)
        if (version != null) finishSuccess(appContext.getString(R.string.install_dialog_firmware_done))
        else finishError(appContext.getString(R.string.install_dialog_firmware_failed), _uiState.value.detail)
    }

    fun installLicense(uriString: String) = runInstall(InstallOperation.License) {
        val path = resolveInstallSource(uriString) ?: return@runInstall finishError(
            appContext.getString(R.string.install_dialog_license_failed)
        )
        if (Ps3InstallBridge.installLicense(appContext, path)) {
            finishSuccess(appContext.getString(R.string.install_dialog_license_done))
        } else {
            finishError(appContext.getString(R.string.install_dialog_license_failed), _uiState.value.detail)
        }
    }

    fun installPkg(uriString: String) = installContent(listOf(uriString))

    fun installContent(uriStrings: List<String>) = runInstall(InstallOperation.Pkg) {
        val paths = uriStrings.mapNotNull(::resolveInstallSource)
        if (paths.size != uriStrings.size) return@runInstall finishError(
            appContext.getString(R.string.install_dialog_pkg_failed)
        )
        val prepared = try {
            ArchiveContentInstaller.prepare(appContext, paths.map(::File)) { archiveName ->
                _uiState.value = _uiState.value.copy(
                    progress = 0f,
                    indeterminate = true,
                    detail = appContext.getString(R.string.install_dialog_extracting_archive, archiveName),
                )
            }
        } catch (error: ArchivePreparationException) {
            return@runInstall finishError(
                appContext.getString(R.string.install_dialog_pkg_failed),
                archiveErrorMessage(error.reason),
            )
        }
        prepared.temporaryRoot?.let(temporaryRootsToCleanup::add)
        if (Ps3InstallBridge.installContent(appContext, prepared.files.map(File::getAbsolutePath))) {
            finishSuccess(appContext.getString(R.string.install_dialog_pkg_done))
        } else {
            finishError(appContext.getString(R.string.install_dialog_pkg_failed), _uiState.value.detail)
        }
    }

    private fun runInstall(operation: InstallOperation, block: suspend () -> Unit) {
        if (_uiState.value.status == InstallStatus.Running) return
        stagedFilesToCleanup.clear()
        temporaryRootsToCleanup.clear()
        _uiState.value = SetupInstallUiState(
            status = InstallStatus.Running,
            operation = operation,
            indeterminate = true,
        )
        viewModelScope.launch(Dispatchers.IO) {
            Ps3InstallBridge.setListener(::handleProgress)
            try {
                block()
            } catch (error: Ps3Runtime.LicenseInstallException) {
                finishError(appContext.getString(R.string.install_dialog_license_failed), error.message)
            } catch (error: Throwable) {
                finishError(appContext.getString(R.string.install_dialog_unexpected_error), error.message)
            } finally {
                stagedFilesToCleanup.forEach { DocumentPathResolver.cleanupStagedFile(appContext, it) }
                stagedFilesToCleanup.clear()
                temporaryRootsToCleanup.forEach(File::deleteRecursively)
                temporaryRootsToCleanup.clear()
                Ps3InstallBridge.setListener(null)
            }
        }
    }

    private fun resolveInstallSource(uriString: String): String? =
        DocumentPathResolver.resolveFilePath(appContext, uriString, copyToCache = true)?.also {
            stagedFilesToCleanup += it
        }

    private fun handleProgress(progress: NativeInstallProgress) {
        _uiState.value = _uiState.value.copy(
            progress = (progress.progress.coerceIn(0f, 1f) * 100f),
            indeterminate = progress.total <= 0f,
            current = progress.current.takeIf { it > 0f }?.roundToInt(),
            total = progress.total.takeIf { it > 0f }?.roundToInt(),
            detail = progress.detail?.takeIf(String::isNotBlank),
        )
    }

    private fun finishSuccess(message: String) {
        InstallStateBus.notifyCompleted()
        _uiState.value = _uiState.value.copy(
            status = InstallStatus.Success,
            progress = 100f,
            indeterminate = false,
            current = null,
            total = null,
            detail = null,
            message = message,
        )
    }

    private fun finishError(message: String, detail: String? = null) {
        _uiState.value = _uiState.value.copy(
            status = InstallStatus.Error,
            current = null,
            total = null,
            message = message,
            detail = detail?.takeIf(String::isNotBlank),
        )
    }

    private fun archiveErrorMessage(reason: ArchivePreparationError): String = appContext.getString(
        when (reason) {
            ArchivePreparationError.MissingVolume -> R.string.install_archive_missing_volume
            ArchivePreparationError.PasswordProtected -> R.string.install_archive_password_protected
            ArchivePreparationError.UnsafeEntry -> R.string.install_archive_unsafe
            ArchivePreparationError.NotEnoughSpace -> R.string.install_archive_not_enough_space
            ArchivePreparationError.NoInstallableContent -> R.string.install_archive_no_content
            ArchivePreparationError.ExtractionFailed -> R.string.install_archive_failed
        }
    )

}
