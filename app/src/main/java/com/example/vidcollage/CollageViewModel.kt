package com.example.vidcollage

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidcollage.pipeline.ProcessingState
import com.example.vidcollage.pipeline.VideoResult
import com.example.vidcollage.pipeline.VideoProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the processing job. All of the heavy work happens on [Dispatchers.Default]; the UI only ever
 * observes [state].
 */
class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private var job: Job? = null

    fun process(uris: List<Uri>) {
        if (uris.isEmpty()) return
        job?.cancel()
        releaseResults()

        job = viewModelScope.launch {
            val results = mutableListOf<VideoResult>()
            val failures = mutableListOf<ProcessingState.Failure>()

            withContext(Dispatchers.Default) {
                VideoProcessor(getApplication()).use { processor ->
                    uris.forEachIndexed { index, uri ->
                        val name = displayNameOf(uri)
                        try {
                            results += processor.process(uri, name) { stage, fraction ->
                                _state.value = ProcessingState.Running(
                                    videoDisplayName = name,
                                    videoIndex = index,
                                    videoCount = uris.size,
                                    stage = stage,
                                    fraction = fraction,
                                )
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            failures += ProcessingState.Failure(
                                name,
                                error.message ?: error.javaClass.simpleName,
                            )
                        }
                    }
                }
            }

            _state.value = ProcessingState.Done(results, failures)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ProcessingState.Idle
    }

    override fun onCleared() {
        job?.cancel()
        releaseResults()
        super.onCleared()
    }

    private fun releaseResults() {
        (_state.value as? ProcessingState.Done)?.results?.forEach { result ->
            result.collage.recycle()
            result.people.forEach { it.shot.bitmap.recycle() }
        }
        _state.value = ProcessingState.Idle
    }

    /** Best-effort human-readable name for a picked video. */
    private fun displayNameOf(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
    }
}
