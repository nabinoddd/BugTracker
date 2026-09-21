package edu.uopeople.bugtracker

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: IssueRepository
    private lateinit var server: MockWebServer
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackerDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer(); server.start()
        repo = IssueRepository(db, Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(IssueApi::class.java))
    }
    @After fun cleanup() { db.close(); server.shutdown() }
    private fun json(id: String, rev: Int = 1, deleted: Boolean = false, title: String = "Login crash") =
        """{"id":"$id","title":"$title","description":"Reproduction","priority":"HIGH","status":"OPEN","createdAt":1000,"revision":$rev,"deleted":$deleted}"""
    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    @Test fun localCrudAndDraft() = runBlocking {
        val d = Draft(title = "Login crash", description = "Reproduction", priority = "HIGH")
        repo.dao.saveDraft(d)
        assertEquals(d, repo.dao.draft())
        val id = repo.submit(d)
        assertNull(repo.dao.draft())
        assertEquals("Login crash", repo.dao.get(id)!!.title)
        repo.submit(d.copy(issueId = id, status = "CLOSED"))
        assertEquals("CLOSED", repo.dao.get(id)!!.status)
        repo.delete(id)
        assertTrue(repo.dao.observe().first().isEmpty())
        assertTrue(repo.dao.get(id)!!.dirty)
        assertTrue(repo.dao.get(id)!!.deleted)
    }
    @Test fun uploadsCreateUpdateDeleteAndPullsOtherDeviceChanges() = runBlocking {
        val id = repo.submit(Draft(title = "Login crash", description = "Reproduction", priority = "HIGH"))
        server.enqueue(response(json(id))); server.enqueue(response("[${json(id)},${json("other")}]"))
        repo.sync()
        assertEquals("PUT", server.takeRequest().method); server.takeRequest()
        assertFalse(repo.dao.get(id)!!.dirty); assertNotNull(repo.dao.get("other"))
        repo.submit(Draft(issueId = id, title = "Fixed", description = "Reproduction"))
        server.enqueue(response(json(id, 2, title = "Fixed")))
        server.enqueue(response("[${json(id, 2, title = "Fixed")},${json("other", 2, true)}]"))
        repo.sync()
        assertEquals("Fixed", repo.dao.get(id)!!.title)
        assertTrue(repo.dao.get("other")!!.deleted)
        repo.delete(id)
        server.enqueue(response(json(id, 3, true))); server.enqueue(response("[${json(id, 3, true)}]"))
        repo.sync()
        assertTrue(repo.dao.get(id)!!.deleted); assertFalse(repo.dao.get(id)!!.dirty)
    }
    @Test fun serverFailureKeepsMutationForRetry() = runBlocking {
        val id = repo.submit(Draft(title = "Login crash"))
        val token = repo.dao.get(id)!!.mutation
        server.enqueue(response("{}", 503))
        try { repo.sync(); fail("Expected HTTP failure") } catch (_: retrofit2.HttpException) {}
        assertTrue(repo.dao.get(id)!!.dirty)
        assertEquals(token, repo.dao.get(id)!!.mutation)
        server.enqueue(response(json(id))); server.enqueue(response("[${json(id)}]"))
        repo.sync(); assertFalse(repo.dao.get(id)!!.dirty)
    }
    @Test fun conflictDoesNotOverwriteLocalAndCanBeResolved() = runBlocking {
        val id = repo.submit(Draft(title = "My title"))
        server.enqueue(response("{}", 409)); server.enqueue(response("[${json(id, 3)}]"))
        repo.sync()
        assertEquals("My title", repo.dao.get(id)!!.title)
        assertNotNull(repo.dao.get(id)!!.error)
        server.enqueue(response(json(id, 3)))
        repo.resolve(id, true)
        assertEquals(3L, repo.dao.get(id)!!.revision)
        assertNull(repo.dao.get(id)!!.error); assertTrue(repo.dao.get(id)!!.dirty)
    }
    @Test fun inputValidationDoesNotClearDraft() = runBlocking {
        val d = Draft(title = " ")
        repo.dao.saveDraft(d)
        try { repo.submit(d); fail("Blank title accepted") } catch (_: IllegalArgumentException) {}
        assertEquals(d, repo.dao.draft())
    }
    @Test fun diskDatabaseSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase("persistence-test.db")
        var disk = Room.databaseBuilder(context, TrackerDatabase::class.java, "persistence-test.db").build()
        disk.dao().put(Issue(id = "durable", title = "Offline", description = "Still saved"))
        disk.dao().saveDraft(Draft(title = "Unfinished"))
        disk.close()
        disk = Room.databaseBuilder(context, TrackerDatabase::class.java, "persistence-test.db").build()
        assertTrue(disk.dao().get("durable")!!.dirty)
        assertEquals("Unfinished", disk.dao().draft()!!.title)
        disk.close(); context.deleteDatabase("persistence-test.db")
    }
    @Test fun editDuringUploadRemainsPending() = runBlocking {
        val id = repo.submit(Draft(title = "First"))
        val fake = object : IssueApi {
            override suspend fun all() = listOf(RemoteIssue(id, "First", "", "MEDIUM", "OPEN", 1000, 1))
            override suspend fun one(id: String) = all().first()
            override suspend fun put(id: String, body: Mutation): RemoteIssue {
                repo.submit(Draft(issueId = id, title = "Second"))
                return one(id)
            }
            override suspend fun delete(id: String, revision: Long, mutation: String) = one(id)
        }
        IssueRepository(db, fake).sync()
        assertEquals("Second", repo.dao.get(id)!!.title)
        assertTrue(repo.dao.get(id)!!.dirty)
        assertEquals(1L, repo.dao.get(id)!!.revision)
    }
    @Test fun offlineFailurePreservesIssue() = runBlocking {
        val id = repo.submit(Draft(title = "Offline"))
        val offline = object : IssueApi {
            override suspend fun all(): List<RemoteIssue> = throw IOException("Offline")
            override suspend fun one(id: String): RemoteIssue = throw IOException("Offline")
            override suspend fun put(id: String, body: Mutation): RemoteIssue = throw IOException("Offline")
            override suspend fun delete(id: String, revision: Long, mutation: String): RemoteIssue = throw IOException("Offline")
        }
        try { IssueRepository(db, offline).sync(); fail("Expected network error") } catch (_: IOException) {}
        assertEquals("Offline", repo.dao.get(id)!!.title)
        assertTrue(repo.dao.get(id)!!.dirty)
    }
}
