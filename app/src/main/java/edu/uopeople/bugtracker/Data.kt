package edu.uopeople.bugtracker

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import retrofit2.http.*
import java.util.UUID

@Entity(tableName = "issues")
data class Issue(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val priority: String = "MEDIUM",
    val status: String = "OPEN",
    val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = 0,
    val dirty: Boolean = true,
    val deleted: Boolean = false,
    val mutation: String = UUID.randomUUID().toString(),
    val error: String? = null
)
@Entity(tableName = "drafts")
data class Draft(@PrimaryKey val key: Int = 1, val issueId: String? = null,
    val title: String = "", val description: String = "",
    val priority: String = "MEDIUM", val status: String = "OPEN")
@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val key: Int = 1, val message: String)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM issues WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observe(): Flow<List<Issue>>
    @Query("SELECT * FROM issues WHERE dirty = 1 AND error IS NULL")
    suspend fun pending(): List<Issue>
    @Query("SELECT * FROM issues WHERE id = :id")
    suspend fun get(id: String): Issue?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(issue: Issue)
    @Query("DELETE FROM issues WHERE id = :id") suspend fun purge(id: String)
    @Query("SELECT * FROM issues WHERE error IS NOT NULL") fun problems(): Flow<List<Issue>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(draft: Draft)
    @Query("SELECT * FROM drafts WHERE `key` = 1") suspend fun draft(): Draft?
    @Query("DELETE FROM drafts") suspend fun clearDraft()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun syncState(state: SyncState)
    @Query("SELECT * FROM sync_state WHERE `key` = 1") fun syncState(): Flow<SyncState?>
}
@Database(entities = [Issue::class, Draft::class, SyncState::class], version = 1, exportSchema = false)
abstract class TrackerDatabase : RoomDatabase() { abstract fun dao(): TrackerDao }

data class RemoteIssue(val id: String, val title: String, val description: String,
    val priority: String, val status: String, val createdAt: Long,
    val revision: Long, val deleted: Boolean = false) {
    fun local() = Issue(id, title, description, priority, status, createdAt,
        revision, dirty = false, deleted = deleted)
}
data class Mutation(val title: String, val description: String, val priority: String,
    val status: String, val createdAt: Long, val baseRevision: Long, val mutation: String)
interface IssueApi {
    @GET("issues") suspend fun all(): List<RemoteIssue>
    @GET("issues/{id}") suspend fun one(@Path("id") id: String): RemoteIssue
    @PUT("issues/{id}") suspend fun put(@Path("id") id: String, @Body body: Mutation): RemoteIssue
    @DELETE("issues/{id}") suspend fun delete(@Path("id") id: String,
        @Query("baseRevision") revision: Long, @Query("mutation") mutation: String): RemoteIssue
}
