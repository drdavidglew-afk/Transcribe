package app.vidscribe

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data class Working(val stage: String, val fraction: Float?) : UiState
    data class Error(val message: String) : UiState
    data class Done(val transcript: Transcript) : UiState
}

enum class Mode { LINK, PODCASTS }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    private val pipeline = AudioPipeline(app)
    private val transcriber = Transcriber(settings)
    private val podcastRepo = PodcastRepo()

    var mode by mutableStateOf(Mode.LINK)

    // Podcast browser
    var podcastQuery by mutableStateOf("")
    var podcastResults by mutableStateOf<List<Podcast>>(emptyList())
        private set
    var openFeed by mutableStateOf<PodcastFeed?>(null)
        private set
    var podcastLoading by mutableStateOf(false)
        private set
    var podcastError by mutableStateOf<String?>(null)
        private set

    var link by mutableStateOf("")
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set
    /** Non-null = the link couldn't be fetched; show the "please download it" dialog with this detail. */
    var linkProblem by mutableStateOf<String?>(null)
        private set
    var showTimes by mutableStateOf(true)

    private var job: Job? = null
    val busy get() = state is UiState.Working

    private val report: Progress = { stage, fraction -> state = UiState.Working(stage, fraction) }

    fun transcribeLink() {
        val url = Regex("https?://\\S+").find(link)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
        if (url == null) {
            state = UiState.Error("That doesn't look like a link. Paste a web address starting with http.")
            return
        }
        link = url
        launchJob {
            val audio = try {
                pipeline.fromUrl(url, report)
            } catch (e: LinkFailedException) {
                state = UiState.Idle
                linkProblem = e.detail
                return@launchJob
            }
            transcribe(audio)
        }
    }

    fun transcribeFile(uri: Uri) {
        linkProblem = null
        launchJob { transcribe(pipeline.fromFile(uri, report)) }
    }

    private suspend fun transcribe(audio: AudioResult) {
        val parts = if (audio.kbps != null) Mp3Splitter.split(audio.file, audio.kbps, AudioPipeline.MAX_BYTES)
                    else listOf(audio.file to 0.0)
        val segments = mutableListOf<Segment>()
        parts.forEachIndexed { i, (file, offset) ->
            val label = if (parts.size > 1) "Transcribing part ${i + 1} of ${parts.size}…"
                        else "Transcribing… (usually under a minute, longer for long recordings)"
            segments += withRateLimitRetry(label, i.toFloat() / parts.size, parts.size > 1) {
                transcriber.transcribe(file)
            }.map { it.copy(start = it.start + offset, end = it.end + offset) }
        }
        if (segments.isEmpty()) throw Exception("No speech was found in this video.")
        state = UiState.Working("Formatting…", null)
        val t = Transcript(audio.title, audio.source, TranscriptFormatter.paragraphs(segments))
        state = UiState.Done(t)
        audio.file.parentFile?.deleteRecursively()
    }

    /** Free API tiers limit audio per hour; wait it out (up to ~5 min each time) instead of failing. */
    private suspend fun <T> withRateLimitRetry(label: String, fraction: Float, showFraction: Boolean, call: suspend () -> T): T {
        var attempts = 0
        while (true) {
            state = UiState.Working(label, if (showFraction) fraction else null)
            try {
                return call()
            } catch (e: RateLimitException) {
                attempts++
                if (attempts > 6 || e.waitSeconds > 300) throw e
                for (left in e.waitSeconds downTo 1) {
                    state = UiState.Working("Usage limit reached — waiting ${left}s, then continuing…", null)
                    delay(1000)
                }
            }
        }
    }

    // ---------------------------------------------------------------- podcasts

    fun searchPodcasts() {
        val q = podcastQuery.trim()
        if (q.isEmpty() || podcastLoading) return
        if (q.startsWith("http")) { openPodcastFeed(q); return }
        viewModelScope.launch {
            podcastLoading = true; podcastError = null
            try {
                podcastResults = podcastRepo.search(q)
                if (podcastResults.isEmpty()) podcastError = "No podcasts found for \"$q\"."
            } catch (e: Exception) {
                podcastError = e.message ?: "Search failed. Check your connection."
            } finally { podcastLoading = false }
        }
    }

    fun openPodcastFeed(feedUrl: String) {
        if (podcastLoading) return
        viewModelScope.launch {
            podcastLoading = true; podcastError = null
            try {
                openFeed = podcastRepo.feed(feedUrl)
            } catch (e: Exception) {
                podcastError = "Couldn't load this podcast's episodes. ${e.message.orEmpty()}"
            } finally { podcastLoading = false }
        }
    }

    fun closeFeed() { openFeed = null; podcastError = null }

    fun transcribeEpisode(feed: PodcastFeed, ep: Episode) {
        launchJob {
            val audio = try {
                pipeline.fromUrl(
                    ep.audioUrl, report,
                    titleOverride = ep.title,
                    sourceOverride = listOf(feed.name, ep.date).filter { it.isNotBlank() }.joinToString(" · "),
                    durationHint = ep.durationSec,
                )
            } catch (e: LinkFailedException) {
                state = UiState.Idle
                linkProblem = e.detail
                return@launchJob
            }
            transcribe(audio)
        }
    }

    /** Leave the transcript and go back to the link box / episode list. */
    fun backFromResult() { state = UiState.Idle }

    private fun launchJob(block: suspend () -> Unit) {
        if (busy) return
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UiState.Error(e.message ?: "Something went wrong.")
            }
        }
    }

    fun cancel() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(AudioPipeline.PROCESS_ID) }
        job?.cancel()
        state = UiState.Idle
    }

    fun dismissLinkProblem() { linkProblem = null }

    fun clear() {
        state = UiState.Idle
        link = ""
    }

    fun transcriptText(): String? =
        (state as? UiState.Done)?.transcript?.let { TranscriptFormatter.asPlainText(it, showTimes) }

    /** Returns null on success, or an error message. */
    fun saveTo(uri: Uri): String? {
        val text = transcriptText() ?: return "Nothing to save."
        return try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            null
        } catch (e: Exception) {
            "Couldn't save the file: ${e.message}"
        }
    }
}
