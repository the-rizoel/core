package com.maxrave.data.repository

import com.maxrave.data.db.datasource.AnalyticsDatasource
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.repository.AnalyticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

private const val TAG = "AnalyticsRepositoryImpl"

internal class AnalyticsRepositoryImpl(
    private val analyticsDatasource: AnalyticsDatasource
): AnalyticsRepository {
    override suspend fun insertPlaybackEvent(playbackEvent: PlaybackEventEntity): Flow<Long> = flow {
        emit(analyticsDatasource.insertPlaybackEvent(playbackEvent))
    }.flowOn(Dispatchers.IO)

    override suspend fun getPlaybackEventsByOffset(
        offset: Int,
        limit: Int
    ): Flow<List<PlaybackEventEntity>> = flow {
        emit(analyticsDatasource.getPlaybackEventsByOffset(offset, limit))
    }.flowOn(Dispatchers.IO)

    override suspend fun getPlaybackEventsByOffsetAndTimestamp(
        offset: Int,
        limit: Int,
        cutoffTimestamp: LocalDateTime
    ): Flow<List<PlaybackEventEntity>> = flow {
        emit(analyticsDatasource.getPlaybackEventsByOffsetAndTimestamp(offset, limit, cutoffTimestamp))
    }.flowOn(Dispatchers.IO)

    override suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime) = withContext(Dispatchers.IO) {
        analyticsDatasource.deleteOldPlaybackEvents(cutoffTimestamp)
    }

}