package dev.halcamera.camera

import android.content.Context
import android.net.Uri
import android.os.Handler

/** Android storage/compositor adapter shared by both camera APIs. */
internal fun pipMedia(context: Context, main: Handler, compositor: () -> DeviceCompositor?,
    available: () -> Boolean, photoFrame: (Long) -> Unit, recordingChanged: (Boolean) -> Unit,
    notice: (String) -> Unit,
    rawPhoto: ((String?, (Result<PhotoResult>) -> Unit) -> Unit)? = null) = PipMedia<PhotoResult, Uri>(
    dispatch = { main.post(it) }, available = available,
    capturePhoto = { requestId, done ->
        fun compose(raw: PhotoResult?) { checkNotNull(compositor()).capture { photo ->
            val library = MediaLibrary(context)
            val result = photo.mapCatching {
                photoFrame(it.imageTimestampNs)
                val name = "${raw?.name ?: library.name()}_PIP.jpg"
                val artifact = library.saveFile(name, "image/jpeg") { stream -> stream.write(it.bytes) }
                val uri = artifact.uri
                PhotoResult(requestId, name, raw?.sensorTimestamp ?: 0, listOf(uri) + raw?.uris.orEmpty(),
                    listOf(artifact) + raw?.artifacts.orEmpty())
            }
            if (result.isFailure) library.deleteAll(raw?.uris.orEmpty())
            done(result)
        } }
        if (rawPhoto == null) compose(null)
        else rawPhoto(requestId) { result -> result.fold(::compose) { done(Result.failure(it)) } }
    },
    startVideo = { audio, done -> checkNotNull(compositor()).startVideo(MediaLibrary(context).name(), audio, done) },
    stopVideo = { done -> checkNotNull(compositor()).stopVideo(true) { done(it.map(Uri::parse)) } },
    recordingChanged = recordingChanged, notice = notice,
)
