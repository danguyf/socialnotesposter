package com.fivesided.socialnotesposter

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

object ApiClient {

    private lateinit var retrofit: Retrofit
    private lateinit var authStorage: AuthStorage

    lateinit var service: WordPressApiService

    fun init(context: Context) {
        authStorage = AuthStorage(context)
        val (blogUrl, _, _) = authStorage.getCredentials()

        // If the URL is blank, use a valid placeholder to prevent a crash.
        // The app logic will still force the user to enter a real URL later.
        val baseUrl = if (blogUrl.isNullOrBlank()) {
            "https://placeholder.com/"
        } else {
            blogUrl.removeSuffix("/") + "/"
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", authStorage.getAuthHeader())
                    .build()
                chain.proceed(request)
            }
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        service = retrofit.create(WordPressApiService::class.java)
    }
}

interface WordPressApiService {
    @POST("wp-json/wp/v2/jetpack-social-note")
    suspend fun postNote(@Body note: SocialNotePost): Response<Unit>
}

data class SocialNotePost(val content: String, val status: String)
