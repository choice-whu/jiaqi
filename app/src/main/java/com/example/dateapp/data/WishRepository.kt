package com.example.dateapp.data

import com.example.dateapp.data.local.WishDao
import com.example.dateapp.data.local.WishItem
import kotlinx.coroutines.flow.Flow

class WishRepository(
    private val wishDao: WishDao
) {

    fun getAllWishItems(): Flow<List<WishItem>> = wishDao.getAllWishItems()

    fun getAllUnvisitedWishItems(): Flow<List<WishItem>> = wishDao.getAllUnvisitedWishItems()

    suspend fun insertWishItem(item: WishItem): Long = wishDao.insertWishItem(item)

    suspend fun updateWishItem(item: WishItem) = wishDao.updateWishItem(item)

    suspend fun deleteWishItem(item: WishItem) = wishDao.deleteWishItem(item)
}
