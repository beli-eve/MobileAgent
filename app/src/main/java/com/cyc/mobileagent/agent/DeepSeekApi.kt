package com.cyc.mobileagent.agent

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization")
        authorization: String,

        @Body
        request: ChatRequest
    ): Response<ChatResponse>
}