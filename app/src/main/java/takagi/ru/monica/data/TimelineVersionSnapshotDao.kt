package takagi.ru.monica.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimelineVersionSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: TimelineVersionSnapshot): Long

    @Query("SELECT * FROM timeline_version_snapshots WHERE operation_log_id = :operationLogId LIMIT 1")
    suspend fun getByOperationLogId(operationLogId: Long): TimelineVersionSnapshot?

    @Query("SELECT * FROM timeline_version_snapshots WHERE operation_log_id IN (:operationLogIds)")
    suspend fun getByOperationLogIds(operationLogIds: List<Long>): List<TimelineVersionSnapshot>

    @Query("DELETE FROM timeline_version_snapshots WHERE created_at < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    @Query("DELETE FROM timeline_version_snapshots WHERE operation_log_id = :operationLogId")
    suspend fun deleteByOperationLogId(operationLogId: Long)
}
