package com.nearby.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nearby.data.local.entity.ConnectionState
import com.nearby.data.local.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE connectionState = :state ORDER BY lastSeen DESC")
    fun getPeersByState(state: ConnectionState): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE connectionState IN (:states) ORDER BY lastSeen DESC")
    fun getPeersByStates(states: List<ConnectionState>): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE id = :peerId")
    suspend fun getPeerById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE id = :peerId")
    fun getPeerByIdFlow(peerId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE endpointId = :endpointId")
    suspend fun getPeerByEndpointId(endpointId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeers(peers: List<PeerEntity>)

    @Update
    suspend fun updatePeer(peer: PeerEntity)

    @Query("UPDATE peers SET connectionState = :state, lastSeen = :lastSeen WHERE id = :peerId")
    suspend fun updateConnectionState(peerId: String, state: ConnectionState, lastSeen: Long = System.currentTimeMillis())

    @Query("UPDATE peers SET endpointId = :endpointId WHERE id = :peerId")
    suspend fun updateEndpointId(peerId: String, endpointId: String?)

    @Query("UPDATE peers SET isVerified = :isVerified WHERE id = :peerId")
    suspend fun updateVerificationStatus(peerId: String, isVerified: Boolean)

    @Query("DELETE FROM peers WHERE id = :peerId")
    suspend fun deletePeer(peerId: String)

    @Query("DELETE FROM peers")
    suspend fun deleteAll()

    @Query("UPDATE peers SET connectionState = :state WHERE connectionState = :fromState")
    suspend fun resetConnectionStates(fromState: ConnectionState, state: ConnectionState)

    @Query("SELECT * FROM peers WHERE lastSyncedAt IS NOT NULL ORDER BY lastSeen DESC")
    fun getSyncedPeers(): Flow<List<PeerEntity>>

    @Query("UPDATE peers SET lastSyncedAt = :timestamp WHERE id = :peerId")
    suspend fun updateLastSyncedAt(peerId: String, timestamp: Long)
}
