package dev.app8.cloud.internal.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Collapses concurrent requests for the same key into one in-flight job (iOS `InFlightCoalescer`).
 * The job runs in the coalescer's own scope, so one caller's cancellation does not cancel the
 * shared work for the others.
 */
internal class InFlightCoalescer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val tasks = HashMap<String, Deferred<ByteArray>>()

    suspend fun run(key: String, work: suspend () -> ByteArray): ByteArray {
        val task = synchronized(lock) {
            tasks[key] ?: run {
                // Lazy so the job is in the map before it can finish and remove itself.
                val job = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        work()
                    } finally {
                        complete(key)
                    }
                }
                tasks[key] = job
                job.start()
                job
            }
        }
        return task.await()
    }

    private fun complete(key: String) {
        synchronized(lock) { tasks.remove(key) }
    }
}
