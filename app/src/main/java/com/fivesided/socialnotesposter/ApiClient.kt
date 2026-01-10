package com.fivesided.socialnotesposter

import android.content.Context
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.Date

object ApiClient {

    private lateinit var retrofit: Retrofit
    private lateinit var authStorage: AuthStorage

    lateinit var service: WordPressApiService

    fun init(context: Context) {
        authStorage = AuthStorage(context)
        val (blogUrl, _, _) = authStorage.getCredentials()

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

        val gson = GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        service = retrofit.create(WordPressApiService::class.java)
    }
}

interface WordPressApiService {
    @POST("wp-json/wp/v2/jetpack-social-note")
    suspend fun postNote(@Body note: SocialNoteRequest): Response<SocialNoteResponse>

    @GET("wp-json/wp/v2/jetpack-social-note")
    suspend fun getDrafts(@Query("status") status: String = "draft"): Response<List<SocialNoteResponse>>

    @POST("wp-json/wp/v2/jetpack-social-note/{id}")
    suspend fun updateNote(@Path("id") id: Int, @Body note: SocialNoteRequest): Response<SocialNoteResponse>

    @DELETE("wp-json/wp/v2/jetpack-social-note/{id}")
    suspend fun deleteNote(@Path("id") id: Int, @Query("force") force: Boolean = true): Response<Unit>
}

data class SocialNoteRequest(
    val content: String,
    val status: String
)

data class SocialNoteResponse(
    val id: Int,
    val content: Content,
    val status: String,
    val modified_gmt: Date
)

data class Content(
    val raw: String?,
    val rendered: String
)
