package com.github.lodestone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.lodestone.data.repository.InstallStage
import com.github.lodestone.data.repository.VersionInstaller
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.VersionChannel
import com.github.lodestone.domain.model.version.VersionEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class HomeUiState(
    val versions: List<VersionEntry> = emptyList(),
    val channel: VersionChannel = VersionChannel.RELEASE,
    val isLoading: Boolean = false,
    val installing: String? = null,
    val progressLabel: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
) {
    val visibleVersions: List<VersionEntry>
        get() = versions.filter { it.channel == channel }
}

/**
 * Drives the version list and installation.
 *
 * The install runs in [viewModelScope] so that leaving the screen cancels it. That is deliberate
 * rather than incidental: every download lands in a `.part` file and is only renamed once verified,
 * so a cancelled install resumes cleanly instead of leaving a truncated jar behind.
 */
class HomeViewModel(private val installer: VersionInstaller) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var installJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            runCatching { installer.fetchManifest() }
                .onSuccess { manifest ->
                    _uiState.update {
                        it.copy(isLoading = false, versions = manifest.versions)
                    }
                }
                .onFailure { failure ->
                    Timber.e(failure, "Could not load the version manifest")
                    _uiState.update {
                        it.copy(isLoading = false, message = failure.message ?: "Network error")
                    }
                }
        }
    }

    fun selectChannel(channel: VersionChannel) {
        _uiState.update { it.copy(channel = channel) }
    }

    fun install(entry: VersionEntry) {
        if (installJob?.isActive == true) {
            return
        }
        installJob = viewModelScope.launch {
            _uiState.update { it.copy(installing = entry.id, progress = 0f, message = null) }
            runCatching {
                installer.install(entry, launchEnvironment()) { stage -> onStage(stage) }
            }
                .onSuccess { version ->
                    _uiState.update {
                        it.copy(
                            installing = null,
                            progressLabel = null,
                            progress = 1f,
                            message = "Installed ${version.id} — needs Java ${version.javaVersion.majorVersion}",
                        )
                    }
                }
                .onFailure { failure ->
                    Timber.e(failure, "Install of %s failed", entry.id)
                    _uiState.update {
                        it.copy(
                            installing = null,
                            progressLabel = null,
                            message = failure.message ?: "Install failed",
                        )
                    }
                }
        }
    }

    private fun onStage(stage: InstallStage) {
        val label = when (stage) {
            InstallStage.ResolvingVersion -> "Resolving version"
            InstallStage.FetchingAssetIndex -> "Fetching asset index"
            InstallStage.UnpackingNatives -> "Unpacking natives"
            InstallStage.PreparingAssets -> "Preparing assets"
            is InstallStage.Downloading ->
                "${stage.progress.completedFiles}/${stage.progress.totalFiles} files"

            is InstallStage.Done -> null
        }
        val fraction = (stage as? InstallStage.Downloading)?.progress?.fraction
        _uiState.update {
            it.copy(progressLabel = label, progress = fraction ?: it.progress)
        }
    }

    /**
     * Describes this device to the rule engine. Always Linux — Android is Linux as far as every
     * rule in every version manifest is concerned, and claiming otherwise would select the macOS or
     * Windows native classifiers.
     */
    private fun launchEnvironment(): LaunchEnvironment = LaunchEnvironment(
        osVersion = System.getProperty("os.version").orEmpty(),
        osArch = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "amd64"
            else -> "aarch64"
        },
    )
}
