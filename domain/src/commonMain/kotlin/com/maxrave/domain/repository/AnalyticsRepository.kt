package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface AnalyticsRepository {
    suspend fun insertPlaybackEvent(playbackEvent: PlaybackEventEntity): Flow<Long>
    suspend fun getPlaybackEventsByOffset(offset: Int, limit: Int): Flow<List<PlaybackEventEntity>>
    suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime
    ): Flow<List<PlaybackEventEntity>>
    suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime)
}