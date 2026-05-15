package com.example.dateapp.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WishDao {

    @Query(
        """
        SELECT * FROM wish_list
        ORDER BY addedTimestamp DESC
        """
    )
    fun getAllWishItems(): Flow<List<WishItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWishItem(item: WishItem): Long

    @Update
    suspend fun updateWishItem(item: WishItem)

    @Delete
    suspend fun deleteWishItem(item: WishItem)

    @Query(
        """
        SELECT * FROM wish_list
        WHERE isVisited = 0
        ORDER BY addedTimestamp DESC
        """
    )
    fun getAllUnvisitedWishItems(): Flow<List<WishItem>>
}
