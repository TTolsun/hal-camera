package dev.halcamera.camera

/** Snapshot each submitted request's targets; later mode changes cannot rewrite older callbacks. */
internal class RequestStreamRegistry<T : Any> {
    private val requests = java.util.WeakHashMap<T, List<String>>()

    @Synchronized fun track(request: T, streams: List<String>): T {
        requests[request] = streams.toList()
        return request
    }

    @Synchronized operator fun get(request: T): List<String>? = requests[request]
}
