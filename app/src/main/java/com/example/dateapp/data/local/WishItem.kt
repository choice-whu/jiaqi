package com.example.dateapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wish_list")
data class WishItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val category: String,
    val locationKeyword: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isVisited: Boolean = false,
    val addedTimestamp: Long,
    val source: String
)
