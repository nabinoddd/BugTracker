package edu.uopeople.bugtracker

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.HttpException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

open class TrackerApp : Application() {
    open val repository: IssueRepository by lazy {
        val db = Room.databaseBuilder(this, TrackerDatabase::class.java, "tracker.db").build()
        val api = Retrofit.Builder().baseUrl("http://10.0.2.2:8080/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(IssueApi::class.java)
        IssueRepository(db, api)
    }
    override fun onCreate() {
        super.onCreate()
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.KEEP, work)
        requestSync(this)
    }
}
fun requestSync(context: Context) {
    val work = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork("issue-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as TrackerApp).repository
        suspend fun message(text: String) = repo.dao.syncState(SyncState(message = text))
        return try {
            message("Syncing saved changes...")
            repo.sync()
            message("Sync complete. Any conflicts remain available for review.")
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: IOException) {
            message("Offline or server unavailable. Saved changes will retry.")
            Result.retry()
        } catch (e: HttpException) {
            val retry = e.code() == 429 || e.code() >= 500
            message(if (retry) "Server busy. Saved changes will retry." else "Server error ${e.code()}. Changes remain saved.")
            if (retry) Result.retry() else Result.failure()
        } catch (e: Exception) {
            message("Sync could not finish. Changes remain saved; retry after correcting the error.")
            Result.failure()
        }
    }
}
