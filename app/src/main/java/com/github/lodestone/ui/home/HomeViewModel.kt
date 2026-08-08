package com.github.lodestone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import com.github.lodestone.BuildConfig
import com.github.lodestone.data.repository.InstallStage
import com.github.lodestone.data.repository.RuntimeInstaller
import com.github.lodestone.data.repository.RuntimeStage
import com.github.lodestone.data.repository.VersionInstaller
import com.github.lodestone.domain.model.version.LaunchEnvironment
import com.github.lodestone.domain.model.version.VersionChannel
import com.github.lodestone.domain.model.version.VersionEntry
import com.github.lodestone.domain.model.account.AccountType
import com.github.lodestone.domain.model.account.MinecraftAccount
import com.github.lodestone.domain.model.launch.LaunchOptions
import com.github.lodestone.domain.model.launch.LaunchRequest
import com.github.lodestone.domain.usecase.BuildLaunchSpecUseCase
import com.github.lodestone.ui.game.GameActivity
import java.io.File
import java.util.UUID
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
    val runtimePrompt: RuntimePrompt? = null,
) {
    val visibleVersions: List<VersionEntry>
        get() = versions.filter { it.channel == channel }
}

/**
 * A pending offer to fetch a Java runtime, raised when Play is pressed on a version whose runtime
 * is missing. The size is carried so it can be shown before anything is downloaded — a runtime is
 * two hundred megabytes, which is not something to start on someone's behalf without asking.
 */
data class RuntimePrompt(
    val entry: VersionEntry,
    val feature: Int,
    val size: String,
)

/**
 * Drives the version list and installation.
 *
 * The install runs in [viewModelScope] so that leaving the screen cancels it. That is deliberate
 * rather than incidental: every download lands in a `.part` file and is only renamed once verified,
 * so a cancelled install resumes cleanly instead of leaving a truncated jar behind.
 */
class HomeViewModel(
    private val installer: VersionInstaller,
    private val runtimeInstaller: RuntimeInstaller,
    private val buildLaunchSpec: BuildLaunchSpecUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var installJob: Job? = null

    /** Why the install could not fetch its runtime, kept until the install reports its result. */
    private var runtimeWarning: String? = null

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
            runtimeWarning = null
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
                            // An install that could not get its runtime still succeeded, but saying
                            // so without saying why would send someone to a Play button that fails.
                            message = runtimeWarning ?: "Installed ${version.id}",
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

    /**
     * Resolves an installed version and hands it to the game process.
     *
     * The account is an offline one for now: sign-in exists but is not wired to a screen yet, and
     * an offline account is enough to reach the main menu, which is what the runtime and graphics
     * work actually needs to be exercised. Release builds refuse to create one.
     */
    fun launch(context: Context, entry: VersionEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "Preparing ${entry.id}…") }
            runCatching { prepareAndStart(context, entry) }
                .onFailure { failure ->
                    Timber.e(failure, "Could not launch %s", entry.id)
                    _uiState.update { it.copy(message = failure.message ?: "Launch failed") }
                }
        }
    }

    /** Accepts the offer raised by [launch], fetching the runtime and then starting the game. */
    fun confirmRuntimeDownload(context: Context) {
        val prompt = _uiState.value.runtimePrompt ?: return
        if (installJob?.isActive == true) {
            return
        }
        installJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    runtimePrompt = null,
                    installing = "Java ${prompt.feature} runtime",
                    progress = 0f,
                    message = null,
                )
            }
            runCatching {
                runtimeInstaller.ensureInstalled(prompt.feature) { stage -> onRuntimeStage(stage) }
                prepareAndStart(context, prompt.entry)
            }.onFailure { failure ->
                Timber.e(failure, "Could not install the Java %d runtime", prompt.feature)
                _uiState.update { it.copy(message = failure.message ?: "Runtime install failed") }
            }
            _uiState.update { it.copy(installing = null, progressLabel = null) }
        }
    }

    fun dismissRuntimePrompt() {
        _uiState.update { it.copy(runtimePrompt = null, message = null) }
    }

    private suspend fun prepareAndStart(context: Context, entry: VersionEntry) {
        val environment = launchEnvironment()
        val version = installer.resolve(entry, environment)
        val result = buildLaunchSpec(
            version = version,
            account = offlineAccount(),
            environment = environment,
            options = LaunchOptions(verboseVmStartup = BuildConfig.DEBUG),
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
        when (result) {
            // Nothing is downloaded here: the offer names the size first, because a runtime is a
            // couple of hundred megabytes and Play is not consent to spend them.
            is BuildLaunchSpecUseCase.Result.MissingRuntime -> {
                val descriptor = runtimeInstaller.describe(result.feature)
                _uiState.update {
                    it.copy(
                        message = null,
                        runtimePrompt = RuntimePrompt(
                            entry = entry,
                            feature = result.feature,
                            size = RuntimeInstaller.describeSize(descriptor),
                        ),
                    )
                }
            }

            is BuildLaunchSpecUseCase.Result.UnsupportedLwjgl -> {
                _uiState.update {
                    it.copy(
                        message = "${entry.id} needs LWJGL ${result.version}, " +
                            "which Lodestone does not support yet",
                    )
                }
            }

            is BuildLaunchSpecUseCase.Result.Ready -> {
                LaunchRequest.from(result.spec)
                    .writeTo(File(context.filesDir, GameActivity.LAUNCH_REQUEST_FILE))
                _uiState.update { it.copy(message = null) }
                context.startActivity(
                    Intent(context, GameActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun offlineAccount(): MinecraftAccount {
        check(BuildConfig.ALLOW_OFFLINE_ACCOUNTS) { "Offline accounts are debug-only" }
        val name = "Player"
        // Mirrors Mojang's scheme for offline identities so the world save directory is stable.
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())
        return MinecraftAccount(
            uuid = uuid.toString().replace("-", ""),
            username = name,
            accessToken = "0",
            expiresAt = Long.MAX_VALUE,
            type = AccountType.OFFLINE,
        )
    }

    private fun onStage(stage: InstallStage) {
        when (stage) {
            InstallStage.ResolvingVersion -> report("Resolving version")
            InstallStage.FetchingAssetIndex -> report("Fetching asset index")
            InstallStage.UnpackingNatives -> report("Unpacking natives")
            InstallStage.PreparingAssets -> report("Preparing assets")
            is InstallStage.Downloading ->
                report(
                    "${stage.progress.completedFiles}/${stage.progress.totalFiles} files",
                    stage.progress.fraction,
                )

            is InstallStage.InstallingRuntime -> onRuntimeStage(stage.stage)
            // Held rather than shown, because the install goes on to report its own result and
            // would otherwise overwrite the one thing worth reading.
            is InstallStage.RuntimeUnavailable -> {
                runtimeWarning = stage.reason
                report(null)
            }

            is InstallStage.Done -> report(null)
        }
    }

    private fun onRuntimeStage(stage: RuntimeStage) {
        when (stage) {
            RuntimeStage.ResolvingManifest -> report("Looking up the Java runtime")
            is RuntimeStage.Downloading -> report(
                "Java ${stage.feature} runtime — ${formatBytes(stage.progress.completedBytes)}" +
                    " of ${formatBytes(stage.progress.totalBytes)}",
                stage.progress.fraction,
            )

            is RuntimeStage.Unpacking -> report("Unpacking the Java ${stage.feature} runtime", 1f)
            is RuntimeStage.Done -> report(null)
        }
    }

    private fun report(label: String?, fraction: Float? = null) {
        _uiState.update {
            it.copy(progressLabel = label, progress = fraction ?: it.progress)
        }
    }

    private fun formatBytes(bytes: Long): String = "%.0f MB".format(bytes / (1024.0 * 1024))

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
