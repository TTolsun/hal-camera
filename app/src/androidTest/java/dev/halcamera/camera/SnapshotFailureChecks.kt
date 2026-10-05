package dev.halcamera.camera

import android.app.Instrumentation
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import dev.halcamera.telemetry.FlightRecorder
import dev.halcamera.telemetry.Telemetry
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Real Handler, ImageProxy adapter and MediaLibrary; camera delivery and the media provider are deterministic fakes. */
object SnapshotFailureChecks {
    fun run(instrumentation: Instrumentation): String {
        val folder = File(instrumentation.targetContext.cacheDir, "snapshot-failure-${UUID.randomUUID()}").apply { mkdirs() }
        val provider = Entries(folder)
        provider.attachInfo(instrumentation.targetContext, ProviderInfo().apply { authority = "snapshot-test" })
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        val library = MediaLibrary(context)
        val main = Handler(Looper.getMainLooper())
        val recorder = FlightRecorder(SystemClock::elapsedRealtimeNanos)
        val telemetry = Telemetry(recorder)
        val results = mutableListOf<Result<PhotoResult>>()
        val notices = mutableListOf<String>()
        val jobs = ArrayDeque<Runnable>()
        var reject = false
        val executor = Executor { if (reject) throw RejectedExecutionException("closed") else jobs.add(it) }
        lateinit var callback: ImageCapture.OnImageCapturedCallback
        lateinit var snapshots: CameraXVideoSnapshot
        var initialized = false
        fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun drain() = instrumentation.waitForIdleSync()
        fun start() { ui { snapshots.capture(null) { results += it } }; drain() }
        fun deliver(image: Frame) { ui { callback.onCaptureSuccess(image) }; drain() }
        val checks = mutableListOf<String>()
        try {
            ui {
                val capture = ImageCapture.Builder().build()
                snapshots = CameraXVideoSnapshot(main, Executor { main.post(it) }, telemetry, "test", library, executor,
                    object : CameraXVideoSnapshot.Host {
                        override val imageCapture = capture
                        override val active = true
                        override val flashName = "OFF"
                        override val zoomRequested = 1f
                        override val snapshotStream = "video_snapshot"
                        override fun updateRotation() = Unit
                        override fun notice(text: String) { notices += text }
                    }, takePicture = { _, _, cb -> callback = cb })
                initialized = true
            }

            // Hold the real media executor past the real 5-second Handler deadline.
            start()
            val first = Frame()
            deliver(first)
            check(first.closes == 1 && snapshots.inFlight && jobs.size == 1)
            var busyFailure: Throwable? = null
            ui { snapshots.capture(null) { busyFailure = it.exceptionOrNull() } }
            check(busyFailure != null && snapshots.inFlight && jobs.size == 1)
            ui { snapshots.release("Recording ended") }
            SystemClock.sleep(5200)
            drain()
            check(results.isEmpty() && snapshots.inFlight)
            jobs.removeFirst().run(); drain()
            check(results.single().isSuccess && !snapshots.inFlight && provider.files.size == 1)
            check(notices.single() == "Snapshot saved. The show goes on.")
            checks += "delayed_save_after_timeout_and_stop"

            // A real MediaLibrary open failure must roll back its just-created row.
            provider.failure = "open"
            start(); deliver(Frame())
            jobs.removeFirst().run(); drain()
            check(results.last().isFailure && !snapshots.inFlight && provider.files.size == 1)
            check(notices.last().contains("ENOSPC"))
            check(recorder.snapshot().count { it.kind == "media_saved" } == 1)
            checks += "storage_open_failure_cleanup_and_notice"

            provider.failure = "write"
            start(); deliver(Frame())
            jobs.removeFirst().run(); drain()
            check(results.last().isFailure && !snapshots.inFlight && provider.files.size == 1)
            check(notices.last().contains("EPIPE")) { notices.last() }
            checks += "storage_write_failure_cleanup"

            provider.failure = "publish"
            start(); deliver(Frame())
            jobs.removeFirst().run(); drain()
            check(results.last().isFailure && provider.files.size == 1 && !snapshots.inFlight)
            checks += "publish_failure_cleanup"

            // A full image buffer exists, but cannot be copied.
            provider.failure = null
            start()
            val unreadable = Frame(broken = true)
            deliver(unreadable)
            check(unreadable.closes == 1 && results.last().isFailure && jobs.isEmpty() && !snapshots.inFlight)
            checks += "copy_failure_closes_buffer"

            reject = true
            start()
            val rejected = Frame()
            deliver(rejected)
            check(rejected.closes == 1 && results.last().isFailure && !snapshots.inFlight)
            check(notices.last().contains("closed before the photo could be saved"))
            checks += "executor_rejection"
            reject = false

            start()
            val beforeStop = results.size
            ui { snapshots.release("Camera closed") }; drain()
            val late = Frame()
            deliver(late)
            ui { callback.onError(ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "late", null)) }; drain()
            check(results.size == beforeStop + 1 && late.closes == 1 && jobs.isEmpty())
            check(notices.last() == "Snapshot failed: Camera closed: photo capture was interrupted.")
            checks += "close_before_image_answers_once"

            start()
            val beforeTimeout = results.size
            SystemClock.sleep(5200); drain()
            val timedOut = Frame()
            deliver(timedOut)
            check(results.size == beforeTimeout + 1 && results.last().isFailure && timedOut.closes == 1)
            check(notices.last() == "Snapshot failed: No photo arrived within 5 seconds.")
            checks += "timeout_discards_late_image"

            start(); deliver(Frame())
            val duplicate = Frame()
            deliver(duplicate)
            check(duplicate.closes == 1 && jobs.size == 1)
            jobs.removeFirst().run(); drain()
            check(results.last().isSuccess && !snapshots.inFlight && provider.files.size == 2)
            check(results.size == notices.size)
            checks += "next_capture_recovers_and_duplicate_image_is_closed"

            // Pair rollback also removes the first written entry when the second create fails.
            provider.failure = "second_insert"
            provider.inserts = 0
            check(runCatching { library.savePair("pair", byteArrayOf(1), byteArrayOf(2)) }.isFailure)
            check(provider.files.size == 2)
            checks += "partial_pair_rollback"
            provider.failure = null
            checks += Camera2SnapshotFallbackCheck.run(context, library)
            return "SNAPSHOT_FAILURE_CHECKS_PASSED=${checks.size}\n" + checks.joinToString("\n") + "\n" + notices.joinToString("\n")
        } finally {
            if (initialized) ui { snapshots.release("Test finished") }
            folder.deleteRecursively()
        }
    }

    private class Frame(private val broken: Boolean = false) : ImageProxy {
        var closes = 0
        override fun close() { closes++ }
        override fun getCropRect() = Rect(0, 0, 2, 2)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getFormat() = ImageFormat.JPEG
        override fun getHeight() = 2
        override fun getWidth() = 2
        override fun getImage(): Image? = null
        override fun getImageInfo() = object : ImageInfo {
            override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
            override fun getTimestamp() = 123L
            override fun getRotationDegrees() = 0
            override fun populateExifData(builder: ExifData.Builder) = Unit
        }
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
            if (broken) throw IllegalStateException("unreadable JPEG")
            return arrayOf(object : ImageProxy.PlaneProxy {
                override fun getRowStride() = 4
                override fun getPixelStride() = 1
                override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
            })
        }
    }

    private class Entries(private val folder: File) : ContentProvider() {
        val files = mutableMapOf<Uri, File>()
        var failure: String? = null
        var inserts = 0
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            inserts++
            if (failure == "second_insert" && inserts == 2) return null
            val entry = Uri.parse("content://snapshot-test/${UUID.randomUUID()}")
            files[entry] = File(folder, entry.lastPathSegment!!)
            return entry
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failure == "open") throw FileNotFoundException("ENOSPC: injected storage failure")
            if (failure == "write") return ParcelFileDescriptor.createPipe().let { pipe ->
                pipe[0].close()
                pipe[1]
            }
            return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
            if (failure == "publish") 0 else 1
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            files.remove(uri)?.delete()
            return 1
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    }
}
