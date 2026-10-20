package com.rtls.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QueuedLocationEntity): Long

    @Query("SELECT * FROM location_queue ORDER BY timestamp_ms ASC, enqueued_at_ms ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<QueuedLocationEntity>

    @Query("SELECT COUNT(*) FROM location_queue")
    suspend fun count(): Int

    @Query("DELETE FROM location_queue WHERE client_msg_id IN (:clientMsgIds)")
    suspend fun deleteByClientMsgIds(clientMsgIds: List<String>): Int

    @Query("DELETE FROM location_queue WHERE enqueued_at_ms < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query(
        """
        DELETE FROM location_queue WHERE client_msg_id IN (
            SELECT client_msg_id FROM location_queue
            ORDER BY enqueued_at_ms ASC
            LIMIT :count
        )
        """,
    )
    suspend fun deleteOldest(count: Int): Int

    @Query(
        """
        UPDATE location_queue
        SET attempt_count = attempt_count + 1
        WHERE client_msg_id IN (:clientMsgIds)
        """,
    )
    suspend fun incrementAttempts(clientMsgIds: List<String>): Int

    @Query("DELETE FROM location_queue")
    suspend fun clear(): Int
}
