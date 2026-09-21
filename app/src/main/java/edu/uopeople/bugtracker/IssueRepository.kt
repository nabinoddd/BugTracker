package edu.uopeople.bugtracker

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.UUID

class IssueRepository(val db: TrackerDatabase, private val api: IssueApi) {
    val dao = db.dao()
    private val syncLock = Mutex()
    suspend fun submit(draft: Draft): String = db.withTransaction {
        require(draft.title.isNotBlank() && draft.title.length <= 120) { "Title must contain 1 to 120 characters." }
        require(draft.description.length <= 10000) { "Description is too long." }
        require(draft.priority in listOf("LOW", "MEDIUM", "HIGH"))
        require(draft.status in listOf("OPEN", "IN_PROGRESS", "CLOSED"))
        val previous = draft.issueId?.let { requireNotNull(dao.get(it)) { "Issue no longer exists." } }
        require(previous?.deleted != true) { "Issue has been deleted." }
        val issue = (previous ?: Issue(title = draft.title, description = draft.description)).copy(
            title = draft.title.trim(), description = draft.description, priority = draft.priority,
            status = draft.status, dirty = true, error = null, mutation = UUID.randomUUID().toString())
        dao.put(issue)
        dao.clearDraft()
        issue.id
    }
    suspend fun delete(id: String) = db.withTransaction {
        dao.get(id)?.let { dao.put(it.copy(deleted = true, dirty = true,
            error = null, mutation = UUID.randomUUID().toString())) }
    }
    suspend fun sync() = syncLock.withLock {
        for (sent in dao.pending()) {
            try {
                val remote = if (sent.deleted) api.delete(sent.id, sent.revision, sent.mutation)
                else api.put(sent.id, Mutation(sent.title, sent.description, sent.priority,
                    sent.status, sent.createdAt, sent.revision, sent.mutation))
                db.withTransaction {
                    val current = dao.get(sent.id)
                    if (current != null) {
                        if (current.mutation == sent.mutation) dao.put(remote.local())
                        else dao.put(current.copy(revision = remote.revision))
                    }
                }
            } catch (e: HttpException) {
                if (e.code() == 429 || e.code() >= 500) throw e
                db.withTransaction {
                    dao.get(sent.id)?.takeIf { it.mutation == sent.mutation }?.let {
                        dao.put(it.copy(error = if (e.code() == 409) "Conflict: choose a version."
                            else "Server rejected this change (${e.code()})."))
                    }
                }
            }
        }
        val snapshot = api.all()
        db.withTransaction {
            for (remote in snapshot) {
                val local = dao.get(remote.id)
                if (local == null || (!local.dirty && remote.revision >= local.revision)) dao.put(remote.local())
            }
        }
    }
    suspend fun resolve(id: String, keepLocal: Boolean) {
        val remote = api.one(id)
        db.withTransaction {
            val current = dao.get(id) ?: return@withTransaction
            if (keepLocal) dao.put(current.copy(revision = remote.revision, error = null,
                dirty = true, mutation = UUID.randomUUID().toString()))
            else dao.put(remote.local())
        }
    }
}
