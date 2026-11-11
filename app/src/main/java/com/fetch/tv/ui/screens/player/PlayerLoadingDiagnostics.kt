package com.fetch.tv.ui.screens.player

import android.os.SystemClock
import androidx.core.net.toUri
import kotlinx.coroutines.flow.update

private const val LOADING_EVENT_LIMIT = 80
private const val LOADING_RAW_EVENT_LIMIT = 120

internal data class PlayerLoadingDiagnosticEvent(
    val timeMs: Long,
    val elapsedMs: Long,
    val phase: String,
    val message: String?,
    val progress: Float?,
    val detail: String?
)

internal fun PlayerRuntimeController.resetLoadingDiagnostics(
    phase: String,
    message: String? = null,
    progress: Float? = null
) {
    val now = SystemClock.elapsedRealtime()
    loadingDiagnosticsStartedAtMs = now
    currentLoadingPhase = phase
    currentLoadingPhaseStartedAtMs = now
    currentLoadingMessageForReport = message
    currentLoadingProgressForReport = progress
    lastLoadingDiagnosticSignature = ""
    startupPhaseSequence = 0
    loadingDiagnosticEvents.clear()
    loadingDiagnosticRawEventLines.clear()
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress)
}

internal fun PlayerRuntimeController.setLoadingStatus(
    phase: String,
    message: String?,
    progress: Float? = null,
    showOverlay: Boolean? = null,
    detail: String? = null
) {
    recordLoadingDiagnosticEvent(phase = phase, message = message, progress = progress, detail = detail)
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = showOverlay ?: state.showLoadingOverlay,
            loadingMessage = message,
            loadingProgress = progress
        )
    }
}

internal fun PlayerRuntimeController.recordLoadingDiagnosticEvent(
    phase: String,
    message: String? = null,
    progress: Float? = null,
    detail: String? = null
) {
    val now = SystemClock.elapsedRealtime()
    val timeMs = System.currentTimeMillis()
    if (loadingDiagnosticsStartedAtMs == 0L) {
        loadingDiagnosticsStartedAtMs = now
        currentLoadingPhaseStartedAtMs = now
    }
    if (phase != currentLoadingPhase) {
        currentLoadingPhase = phase
        currentLoadingPhaseStartedAtMs = now
    }
    currentLoadingMessageForReport = message
    currentLoadingProgressForReport = progress
    val normalizedProgress = progress?.let { "%.3f".format(it.coerceIn(0f, 1f)) }.orEmpty()
    val signature = "$phase|${message.orEmpty()}|$normalizedProgress|${detail.orEmpty()}"
    if (signature == lastLoadingDiagnosticSignature) return
    lastLoadingDiagnosticSignature = signature
    startupPhaseSequence += 1
    val elapsedMs = (now - loadingDiagnosticsStartedAtMs).coerceAtLeast(0L)
    val phaseElapsedMs = (now - currentLoadingPhaseStartedAtMs).coerceAtLeast(0L)
    val clickElapsedMs = launchStartedAtElapsedMs?.let { (now - it).coerceAtLeast(0L) }
    recordLoadingDiagnosticRawEventLine(
        "STARTUP_STAGE: seq=$startupPhaseSequence phase=$phase elapsedMs=$elapsedMs " +
            "phaseElapsedMs=$phaseElapsedMs clickElapsedMs=${clickElapsedMs ?: -1L} " +
            "engine=$currentInternalPlayerEngine host=${currentStreamUrl.safePlaybackRawHost()} " +
            "message=${message?.compactTraceValue() ?: "n/a"} progress=${progress ?: -1f} " +
            "detail=${detail?.compactTraceValue() ?: "n/a"}"
    )
    loadingDiagnosticEvents.addLast(
        PlayerLoadingDiagnosticEvent(
            timeMs = timeMs,
            elapsedMs = elapsedMs,
            phase = phase,
            message = message,
            progress = progress,
            detail = detail
        )
    )
    while (loadingDiagnosticEvents.size > LOADING_EVENT_LIMIT) {
        loadingDiagnosticEvents.removeFirst()
    }
}

internal fun PlayerRuntimeController.finishLoadingDiagnostics(phase: String) {
    recordLoadingDiagnosticEvent(phase = phase, message = null, progress = 1f)
}

private fun PlayerRuntimeController.recordLoadingDiagnosticRawEventLine(line: String) {
    loadingDiagnosticRawEventLines.addLast(line.rawLoadingEventLine())
    while (loadingDiagnosticRawEventLines.size > LOADING_RAW_EVENT_LIMIT) {
        loadingDiagnosticRawEventLines.removeFirst()
    }
}

private fun String.safePlaybackRawHost(): String {
    return runCatching {
        toUri().host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

private fun String.compactTraceValue(): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(160)

private fun String.rawLoadingEventLine(): String =
    replace('\n', ' ')
        .replace('\r', ' ')
        .take(2000)
