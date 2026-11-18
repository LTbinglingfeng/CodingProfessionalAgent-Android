package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import dev.cpa.android.data.repository.RemoteDesktopRepository
import dev.cpa.android.data.repository.git.GitReview
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RemoteGitState(val loading: Boolean = false, val review: GitReview? = null, @param:StringRes val error: Int? = null)
class RemoteGitController(private val connection: String, private val remote: RemoteDesktopRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RemoteGitState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    fun load(path: String?, base: String?, target: String?) {
        val token = ++generation; job?.cancel(); mutable.value = RemoteGitState()
        if (path.isNullOrBlank()) return
        mutable.value = RemoteGitState(loading = true)
        job = scope.launch {
            try {
                val review = remote.gitReview(connection, path, base, target)
                if (token == generation) mutable.value = RemoteGitState(review = review)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.value = RemoteGitState(error = R.string.git_read_failed) }
        }
    }
    fun close() { generation++; job?.cancel(); mutable.update { it.copy(loading = false) } }
}
