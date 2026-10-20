package com.rtls.core.data.queue

import com.rtls.core.model.Crs
import com.rtls.core.model.LocationProvider
import com.rtls.core.model.LocationSample
import java.util.UUID

data class QueuedLocation(
    val clientMsgId: String,
    val sample: LocationSample,
    val enqueuedAtMs: Long,
    val attemptCount: Int,
)

/**
 * Offline queue from protocol §8.3: at most 1,000 entries / 24 hours, oldest
 * evicted first, retries reuse the original `clientMsgId`.
 */
interface LocationQueue {
    suspend fun enqueue(sample: LocationSample, clientMsgId: String = UUID.randomUUID().toString()): String
    suspend fun peek(limit: Int = 100): List<QueuedLocation>
    suspend fun remove(clientMsgIds: List<String>)
    suspend fun markAttempt(clientMsgIds: List<String>)
    suspend fun count(): Int
    suspend fun prune()
    suspend fun clear()
}

class RoomLocationQueue(
    private val dao: com.rtls.core.data.db.LocationQueueDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : LocationQueue {

    override suspend fun enqueue(sample: LocationSample, clientMsgId: String): String {
        val normalized = sample.toWgs84()
        dao.insert(
            com.rtls.core.data.db.QueuedLocationEntity(
                clientMsgId = clientMsgId,
                deviceId = null,
                latitude = normalized.latitude,
                longitude = normalized.longitude,
                sourceCrs = normalized.sourceCrs.name,
                accuracyMeters = normalized.accuracyMeters,
                provider = normalized.provider.wireValue,
                timestampMs = normalized.timestampMs,
                speedMps = normalized.speedMps,
                bearingDegrees = normalized.bearingDegrees,
                isMock = normalized.isMock,
                batteryLevel = normalized.batteryLevel,
                enqueuedAtMs = clock(),
            ),
        )
        prune()
        return clientMsgId
    }

    override suspend fun peek(limit: Int): List<QueuedLocation> {
        require(limit > 0) { "limit must be > 0" }
        return dao.oldest(limit.coerceAtMost(MAX_PEEK)).map { entity ->
            QueuedLocation(
                clientMsgId = entity.clientMsgId,
                sample = LocationSample(
                    latitude = entity.latitude,
                    longitude = entity.longitude,
                    crs = Crs.WGS84,
                    sourceCrs = Crs.valueOf(entity.sourceCrs),
                    accuracyMeters = entity.accuracyMeters,
                    provider = LocationProvider.fromWire(entity.provider),
                    timestampMs = entity.timestampMs,
                    speedMps = entity.speedMps,
                    bearingDegrees = entity.bearingDegrees,
                    isMock = entity.isMock,
                    batteryLevel = entity.batteryLevel,
                ),
                enqueuedAtMs = entity.enqueuedAtMs,
                attemptCount = entity.attemptCount,
            )
        }
    }

    override suspend fun remove(clientMsgIds: List<String>) {
        if (clientMsgIds.isEmpty()) return
        dao.deleteByClientMsgIds(clientMsgIds)
    }

    override suspend fun markAttempt(clientMsgIds: List<String>) {
        if (clientMsgIds.isEmpty()) return
        dao.incrementAttempts(clientMsgIds)
    }

    override suspend fun count(): Int = dao.count()

    override suspend fun prune() {
        dao.deleteOlderThan(clock() - maxAgeMs)
        val excess = dao.count() - maxRows
        if (excess > 0) dao.deleteOldest(excess)
    }

    override suspend fun clear() {
        dao.clear()
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 1_000
        const val DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        private const val MAX_PEEK = 500
    }
}
