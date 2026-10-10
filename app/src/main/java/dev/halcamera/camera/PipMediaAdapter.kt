package dev.halcamera.camera

import android.content.Context
import android.net.Uri
import android.os.Handler

/** Android storage/compositor adapter shared by both camera APIs. */
internal fun pipMedia(context: Context, main: Handler, compositor: () -> DeviceCompositor?,
    available: () -> Boolean, photoFrame: (Long) -> Unit, recordingChanged: (Boolean) -> Unit,
    notice: (String) -> Unit) = PipMedia<PhotoResult, Uri>(
    dispatch = { main.post(it) }, available = available,
    capturePhoto = { requestId, done ->
        checkNotNull(compositor()).capture { photo ->
            done(photo.mapCatching {
                photoFrame(it.imageTimestampNs)
                val library = MediaLibrary(context)
                val name = "${library.name()}_PIP.jpg"
                val uri = library.create(name, false)
                try { library.write(uri) { stream -> stream.write(it.bytes) }; library.publish(uri) }
                catch (e: Exception) { runCatching { library.resolver.delete(uri, null, null) }; throw e }
                PhotoResult(requestId, name, 0, listOf(uri),
                    listOf(PhotoArtifact(name, "image/jpeg", uri, it.bytes.size.toLong())))
            })
        }
    },
    startVideo = { audio, done -> checkNotNull(compositor()).startVideo(MediaLibrary(context).name(), audio, done) },
    stopVideo = { done -> checkNotNull(compositor()).stopVideo(true) { done(it.map(Uri::parse)) } },
    recordingChanged = recordingChanged, notice = notice,
)
