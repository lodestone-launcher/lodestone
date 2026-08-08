package com.github.lodestone.data.remote.download

import com.github.lodestone.common.io.Checksum
import com.github.lodestone.common.io.HashAlgorithm
import com.github.lodestone.common.io.Hashing
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** One file to fetch. [size] and [checksum] are whatever the manifest happened to publish. */
data class DownloadRequest(
    val url: String,
    val destination: File,
    val checksum: Checksum? = null,
    val size: Long? = null,
    /** Shown in the UI while this file is in flight. */
    val label: String = destination.name,
)

/**
 * How hard to work to decide an already-present file is good.
 *
 * A full install touches several thousand files, and hashing all of them costs seconds of wall
 * clock on a phone. [SIZE_ONLY] is the right default for a normal launch because Lodestone only
 * ever moves a file into place after verifying the bytes it just downloaded; [CHECKSUM] exists for
 * an explicit repair, where the point is to catch damage that happened afterwards.
 */
enum class VerificationMode {
    SIZE_ONLY,
    CHECKSUM,
}

data class DownloadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
    val currentLabel: String?,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            totalFiles > 0 -> completedFiles.toFloat() / totalFiles
            else -> 0f
        }
}

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fetches a batch of files concurrently, verifying and resuming as it goes.
 *
 * Every download lands in a sibling `.part` file and is renamed into place only once its bytes have
 * been checked, so an interrupted install can never leave a truncated jar that looks complete on
 * the next run.
 */
class DownloadEngine(
    private val client: HttpClient,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(
        requests: List<DownloadRequest>,
        verification: VerificationMode = VerificationMode.SIZE_ONLY,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        if (requests.isEmpty()) {
            onProgress(DownloadProgress(0, 0, 0, 0, null))
            return
        }

        val totalBytes = requests.sumOf { it.size ?: 0L }
        val completedBytes = AtomicLong()
        val completedFiles = AtomicInteger()
        val semaphore = Semaphore(parallelism)

        fun report(label: String?) {
            onProgress(
                DownloadProgress(
                    completedBytes = completedBytes.get(),
                    totalBytes = totalBytes,
                    completedFiles = completedFiles.get(),
                    totalFiles = requests.size,
                    currentLabel = label,
                ),
            )
        }

        report(null)
        coroutineScope {
            requests.map { request ->
                async(dispatcher) {
                    semaphore.withPermit {
                        val written = fetch(request, verification) { delta ->
                            completedBytes.addAndGet(delta)
                        }
                        // A request with no declared size contributes nothing to the total, so
                        // counting its bytes would push the bar past 100%.
                        if (request.size == null) {
                            completedBytes.addAndGet(-written)
                        }
                        completedFiles.incrementAndGet()
                        report(request.label)
                    }
                }
            }.awaitAll()
        }
        report(null)
    }

    /** Downloads a single file, retrying transient failures. Returns the number of bytes written. */
    private suspend fun fetch(
        request: DownloadRequest,
        verification: VerificationMode,
        onBytes: (Long) -> Unit,
    ): Long {
        if (isSatisfied(request, verification)) {
            // Count a skipped file's bytes so the progress bar reflects work already done.
            onBytes(request.size ?: 0L)
            return request.size ?: 0L
        }

        var lastFailure: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            // Bytes reported by a failed attempt are taken back, so a retry cannot make the
            // progress bar count the same file twice.
            var reported = 0L
            try {
                return transfer(request) { delta ->
                    reported += delta
                    onBytes(delta)
                }
            } catch (failure: IOException) {
                lastFailure = failure
                onBytes(-reported)
                // Partial data is suspect once a transfer fails mid-stream; the next attempt starts
                // clean rather than resuming onto bytes of unknown provenance.
                partFile(request.destination).delete()
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_BASE_DELAY_MILLIS shl attempt)
                }
            }
        }
        throw DownloadException("Failed to download ${request.url}", lastFailure)
    }

    private suspend fun transfer(request: DownloadRequest, onBytes: (Long) -> Unit): Long =
        withContext(dispatcher) {
            val destination = request.destination
            destination.parentFile?.mkdirs()
            val part = partFile(destination)

            // Resume where a previous run stopped. The digest is rebuilt over the existing bytes so
            // the final checksum still covers the whole file.
            val existingBytes = if (part.isFile) part.length() else 0L
            val algorithm = request.checksum?.algorithm ?: HashAlgorithm.SHA1
            val resumeDigest = Hashing.digest(algorithm)
            if (existingBytes > 0) {
                part.inputStream().use { stream ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        resumeDigest.update(buffer, 0, read)
                    }
                }
                onBytes(existingBytes)
            }

            client.prepareGet(request.url) {
                if (existingBytes > 0) {
                    header(HttpHeaders.Range, "bytes=$existingBytes-")
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw DownloadException("HTTP ${response.status.value} for ${request.url}")
                }

                // A server that ignores the range header restarts the body at zero, so the digest
                // and the file both have to be rewound to match.
                val resumed = existingBytes > 0 && response.status == HttpStatusCode.PartialContent
                if (!resumed && existingBytes > 0) {
                    onBytes(-existingBytes)
                }

                val digest = if (resumed) resumeDigest else Hashing.digest(algorithm)
                var written = if (resumed) existingBytes else 0L

                val channel = response.bodyAsChannel()
                RandomAccessFile(part, "rw").use { output ->
                    output.setLength(written)
                    output.seek(written)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onBytes(read.toLong())
                    }
                }

                val actual = Hashing.toHex(digest.digest())
                if (request.checksum != null && !Hashing.matches(request.checksum, actual)) {
                    part.delete()
                    throw DownloadException(
                        "Checksum mismatch for ${request.url}: " +
                            "expected ${request.checksum.value}, got $actual",
                    )
                }
                if (request.size != null && written != request.size) {
                    part.delete()
                    throw DownloadException(
                        "Size mismatch for ${request.url}: expected ${request.size}, got $written",
                    )
                }

                // Rename last: a file appears at its real path only once it is known to be whole.
                destination.delete()
                if (!part.renameTo(destination)) {
                    throw DownloadException("Could not move ${part.name} into place")
                }
                written
            }
        }

    private fun isSatisfied(request: DownloadRequest, verification: VerificationMode): Boolean {
        val destination = request.destination
        if (!destination.isFile) {
            return false
        }
        request.size?.let { expected ->
            if (destination.length() != expected) {
                return false
            }
        }
        // With no size to compare against, the checksum is the only signal available, so it is
        // worth paying for even in the fast mode.
        val needsChecksum = verification == VerificationMode.CHECKSUM || request.size == null
        if (needsChecksum && request.checksum != null) {
            val actual = Hashing.hash(destination, request.checksum.algorithm)
            val matches = Hashing.matches(request.checksum, actual)
            if (!matches) {
                Timber.w("Re-downloading %s: checksum mismatch", destination.name)
            }
            return matches
        }
        return true
    }

    private fun partFile(destination: File) =
        File(destination.parentFile, "${destination.name}.part")

    companion object {
        /**
         * Enough streams to saturate a phone's link without thrashing: asset objects are mostly a
         * few kilobytes each, so throughput is dominated by round trips rather than bandwidth.
         */
        const val DEFAULT_PARALLELISM = 8

        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_BASE_DELAY_MILLIS = 500L
    }
}
