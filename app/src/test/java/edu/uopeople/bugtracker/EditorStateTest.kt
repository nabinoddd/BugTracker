package edu.uopeople.bugtracker

import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class StateTestApp : TrackerApp() {
    override val repository: IssueRepository by lazy {
        val db = Room.inMemoryDatabaseBuilder(this, TrackerDatabase::class.java).allowMainThreadQueries().build()
        val api = object : IssueApi {
            override suspend fun all(): List<RemoteIssue> = emptyList()
            override suspend fun one(id: String): RemoteIssue = error("No network needed")
            override suspend fun put(id: String, body: Mutation): RemoteIssue = error("No network needed")
            override suspend fun delete(id: String, revision: Long, mutation: String): RemoteIssue = error("No network needed")
        }
        IssueRepository(db, api)
    }
    override fun onCreate() {}
}
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = StateTestApp::class)
class EditorStateTest {
    private lateinit var app: StateTestApp
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(Dispatchers.Unconfined); app = ApplicationProvider.getApplicationContext() }
    @After fun cleanup() { stores.forEach { it.clear() }; app.repository.db.close(); Dispatchers.resetMain() }
    private fun model(state: SavedStateHandle, store: ViewModelStore = ViewModelStore().also { stores.add(it) }): EditorViewModel {
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return EditorViewModel(app, state).also { it.scheduleSync = {} } as T
            }
        })[EditorViewModel::class.java]
    }
    private suspend fun awaitReady(m: EditorViewModel) { withTimeout(5000) { while (!m.ready.value) delay(10) } }
    @Test fun retainedStoreKeepsDraftAcrossOwnerRecreation() = runBlocking {
        val store = ViewModelStore().also { stores.add(it) }
        val first = model(SavedStateHandle(), store); awaitReady(first)
        first.change(Draft(title = "Rotation draft", description = "Still typing", priority = "HIGH"))
        val recreated = model(SavedStateHandle(), store)
        assertSame(first, recreated)
        assertEquals("Rotation draft", recreated.draft.value.title)
        assertEquals("HIGH", recreated.draft.value.priority)
    }
    @Test fun savedStateRestoresAllEditorFields() = runBlocking {
        val state = SavedStateHandle(mapOf("issueId" to "issue-a", "title" to "Draft",
            "description" to "Reproduction", "priority" to "LOW", "status" to "IN_PROGRESS"))
        val m = model(state); awaitReady(m)
        assertEquals(Draft(issueId="issue-a", title="Draft", description="Reproduction", priority="LOW", status="IN_PROGRESS"), m.draft.value)
    }
    @Test fun repeatedSubmitCreatesOneIssue() = runBlocking {
        val m = model(SavedStateHandle()); awaitReady(m)
        m.change(Draft(title = "One issue"))
        m.submit(); m.submit()
        withTimeout(5000) { while (m.submitting.value) delay(10) }
        assertEquals(1, app.repository.dao.observe().first().size)
        assertEquals("", m.draft.value.title)
        assertNull(app.repository.dao.draft())
    }
}
