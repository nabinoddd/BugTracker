package edu.uopeople.bugtracker

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class EditorViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    val repo = (app as TrackerApp).repository
    val draft = MutableStateFlow(Draft())
    val ready = MutableStateFlow(false)
    val submitting = MutableStateFlow(false)
    internal var scheduleSync: () -> Unit = { requestSync(app) }
    val message = MutableStateFlow("")
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            try {
                val stored = repo.dao.draft() ?: Draft()
                draft.value = if (state.contains("title")) Draft(issueId = state["issueId"],
                    title = state["title"] ?: "", description = state["description"] ?: "",
                    priority = state["priority"] ?: "MEDIUM", status = state["status"] ?: "OPEN") else stored
            } catch (e: Exception) { message.value = "Draft could not be loaded." }
            ready.value = true
            for (action in actions) {
                try { action() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message.value = e.message ?: "Operation failed. Your draft is still available." }
            }
        }
    }
    fun change(value: Draft) {
        draft.value = value
        remember(value)
        actions.trySend { repo.dao.saveDraft(value) }
    }
    private fun remember(value: Draft) {
        state["issueId"] = value.issueId
        state["title"] = value.title
        state["description"] = value.description
        state["priority"] = value.priority
        state["status"] = value.status
    }
    fun edit(issue: Issue) = change(Draft(issueId = issue.id, title = issue.title,
        description = issue.description, priority = issue.priority, status = issue.status))
    fun submit() {
        if (!ready.value || submitting.value) return
        submitting.value = true
        val submitted = draft.value
        actions.trySend {
            try {
            repo.submit(submitted)
            if (draft.value == submitted) {
                draft.value = Draft()
                remember(draft.value)
            }
            message.value = "Issue saved on this device. Sync is scheduled."
            scheduleSync()
            } finally { submitting.value = false }
        }
    }
    fun delete(id: String) { actions.trySend {
        repo.delete(id)
        scheduleSync()
    } }
    fun resolve(id: String, keep: Boolean) { actions.trySend {
        repo.resolve(id, keep)
        scheduleSync()
    } }
}
