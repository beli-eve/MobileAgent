package com.cyc.mobileagent.agent

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private const val BASE_URL =
        "https://api.deepseek.com/"

    /**
     * DeepSeek 网络客户端。
     *
     * connectTimeout：
     * 建立连接最多等待 20 秒。
     *
     * readTimeout：
     * 等待服务器返回数据最多 90 秒。
     *
     * callTimeout：
     * 整个请求最多允许执行 120 秒。
     */
    private val okHttpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(
                20,
                TimeUnit.SECONDS
            )
            .readTimeout(
                90,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                30,
                TimeUnit.SECONDS
            )
            .callTimeout(
                120,
                TimeUnit.SECONDS
            )
            .retryOnConnectionFailure(
                true
            )
            .build()

    private val retrofit: Retrofit =
        Retrofit.Builder()
            .baseUrl(
                BASE_URL
            )
            .client(
                okHttpClient
            )
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()

    val api: DeepSeekApi by lazy {
        retrofit.create(
            DeepSeekApi::class.java
        )
    }
}