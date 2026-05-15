package com.example.dateapp.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AiApiService {

    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatRequest
    ): Response<ResponseBody>
}
