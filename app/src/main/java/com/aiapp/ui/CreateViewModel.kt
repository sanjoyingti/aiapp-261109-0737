package com.aiapp.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.data.ApiKeys
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.media.MediaGen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/** Image and video generation. Lives with the activity, so a video keeps generating if you switch screens. */
class CreateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val keys = ApiKeys(app)

    var video by mutableStateOf(false)
    var imageProvider by mutableStateOf(ProviderId.GEMINI)
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var model by mutableStateOf("")
    var aspect by mutableStateOf("16:9")
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var resultUri by mutableStateOf<Uri?>(null)
        private set
    var resultBytes by mutableStateOf<ByteArray?>(null)
        private set
    var resultIsVideo by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** A photo to edit (images) or animate (video). */
    var reference by mutableStateOf<com.aiapp.llm.Attachment?>(null)

    fun attach(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val a = com.aiapp.data.AttachmentStore.import(app, uri)
                if (!a.isImage) error = "Attach a photo (JPG, PNG or WebP) to edit or animate." else reference = a
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Couldn't attach that file."
            }
        }
    }

    init {
        loadModels()
    }

    fun setMode(isVideo: Boolean) {
        if (video == isVideo) return
        video = isVideo
        loadModels()
    }

    fun setProvider(p: ProviderId) {
        imageProvider = p
        loadModels()
    }

    fun loadModels() {
        val p = if (video) ProviderId.GEMINI else imageProvider
        val key = keys.get(p)
        models = emptyList()
        model = ""
        if (key.isBlank()) {
            error = "Add your ${p.label} API key in Settings to create ${if (video) "videos" else "images"}."
            return
        }
        error = null
        viewModelScope.launch {
            try {
                val list = if (video) MediaGen.videoModels(key) else MediaGen.imageModels(p, key)
                models = list
                model = list.firstOrNull()?.id.orEmpty()
                if (list.isEmpty()) {
                    error = if (video) "Your Gemini key has no video models (Veo) available." else "No image models available for this key."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = friendly(e)
            }
        }
    }

    fun generate(prompt: String) {
        if (busy || prompt.isBlank() || model.isBlank()) return
        val p = if (video) ProviderId.GEMINI else imageProvider
        val key = keys.get(p)
        val isVideo = video
        busy = true
        error = null
        resultUri = null
        resultBytes = null
        status = if (isVideo) "Starting video (this takes a few minutes)…" else "Creating image…"
        job = viewModelScope.launch {
            try {
                val bytes = if (isVideo) {
                    MediaGen.video(model, key, prompt.trim(), aspect, reference) { status = it }
                } else {
                    MediaGen.image(p, model, key, prompt.trim(), reference)
                }
                status = "Saving to your gallery…"
                resultUri = MediaGen.saveToGallery(app, bytes, isVideo)
                resultBytes = if (isVideo) null else bytes
                resultIsVideo = isVideo
                status = "Saved to ${if (isVideo) "Movies" else "Pictures"}/AIApp"
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Throwable) {
                status = null
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) -> "Key rejected or no access to this model: ${t.message}"
        t is ProviderException && t.code == 429 -> "Rate limit or quota reached. Video and image generation need a paid plan on most keys."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }
}
