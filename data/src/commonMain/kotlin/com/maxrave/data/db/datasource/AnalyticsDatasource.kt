package com.maxrave.data.db.datasource

import DatabaseDao
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import kotlinx.datetime.LocalDateTime

internal class AnalyticsDatasource(
    private val databaseDao: DatabaseDao
) {
    suspend fun insertPlaybackEvent(playbackEventEntity: PlaybackEventEntity) = databaseDao.insertPlaybackEvent(playbackEventEntity)

    suspend fun getPlaybackEventsByOffset(offset: Int, limit: Int): List<PlaybackEventEntity> =
        databaseDao.getPlaybackEventsByOffset(offset, limit)

    suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime
    ): List<PlaybackEventEntity> =
        databaseDao.getPlaybackEventsByOffsetAndTimestamp(offset, limit, cutoffTimestamp)

    suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime) =
        databaseDao.deleteOldPlaybackEvents(cutoffTimestamp)
}