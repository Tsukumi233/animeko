package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.utils.platform.Uuid

data class LocalResourceRelocationState(
    val requestId: String = "",
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val plan: LocalResourceRelocationPlan? = null,
    val error: Throwable? = null,
)

/** A dismissed or superseded picker result cannot replace the current confirmation. */
class LocalResourceRelocationController(
    private val scope: CoroutineScope,
    private val prepareFile: suspend (resourceId: String, sourceId: String, uri: String) -> LocalResourceRelocationPlan,
    private val prepareDirectory: suspend (rootId: String, sourceId: String, uri: String) -> LocalResourceRelocationPlan,
    private val commit: suspend (LocalResourceRelocationPlan) -> Unit,
    private val onCommitted: (LocalResourceRelocationPlan) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LocalResourceRelocationState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun file(resourceId: String, sourceId: String, uri: String) = prepare { prepareFile(resourceId, sourceId, uri) }
    fun directory(rootId: String, sourceId: String, uri: String) = prepare { prepareDirectory(rootId, sourceId, uri) }

    private fun prepare(load: suspend () -> LocalResourceRelocationPlan) {
        if (state.value.saving) return
        job?.cancel()
        val request = Uuid.randomString()
        mutableState.value = LocalResourceRelocationState(request, visible = true, loading = true)
        job = scope.launch {
            try {
                val plan = load()
                currentCoroutineContext().ensureActive()
                mutableState.update { if (it.requestId == request) it.copy(loading = false, plan = plan) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { if (it.requestId == request) it.copy(loading = false, error = e) else it }
            }
        }
    }

    fun confirm() {
        val current = state.value
        val plan = current.plan ?: return
        if (!current.visible || current.loading || current.saving || current.error != null) return
        mutableState.value = current.copy(saving = true)
        job = scope.launch {
            try {
                commit(plan)
                currentCoroutineContext().ensureActive()
                if (state.value.requestId == current.requestId) {
                    mutableState.value = LocalResourceRelocationState()
                    onCommitted(plan)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { if (it.requestId == current.requestId) it.copy(saving = false, error = e) else it }
            }
        }
    }

    fun dismiss() {
        if (state.value.saving) return
        job?.cancel()
        mutableState.value = LocalResourceRelocationState()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            useCase: LocalResourceRelocationUseCase,
            resolveSource: suspend (sourceId: String) -> LocalFileMediaSource,
            onCommitted: (LocalResourceRelocationPlan) -> Unit = {},
        ) = LocalResourceRelocationController(scope,
            { resourceId, sourceId, uri -> useCase.prepareFile(resourceId, uri, resolveSource(sourceId)) },
            { rootId, sourceId, uri -> useCase.prepareDirectory(rootId, uri, resolveSource(sourceId)) },
            useCase::confirm, onCommitted)
    }
}
