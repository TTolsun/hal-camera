package dev.halcamera.camera

import android.os.Handler
import java.util.concurrent.Executor

/** Keep the existing main artifacts (including RAW) and add the independently saved secondary source. */
internal fun capturePipPhotoGroup(requestId: String?, captures: List<((Result<PhotoResult>) -> Unit) -> Unit>,
    library: MediaLibrary, io: Executor, main: Handler, done: (Result<PhotoResult>) -> Unit, mainIndex: Int = 0) =
    capturePipBatch(captures, discard = { saved, complete ->
        io.execute { library.deleteAll(saved.flatMap { it.uris }); main.post(complete) }
    }) { result -> done(result.map { photos ->
        val first = photos[mainIndex]
        PhotoResult(requestId, first.name, first.sensorTimestamp, photos.flatMap { it.uris }, photos.flatMap { it.artifacts })
    }) }
