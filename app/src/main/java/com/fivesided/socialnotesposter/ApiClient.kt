package com.fivesided.socialnotesposter

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ApiClient {

    private lateinit var retrofit: Retrofit
    private lateinit var authStorage: AuthStorage

    lateinit var service: WordPressApiService
    lateinit var gson: Gson

    fun init(context: Context) {
        authStorage = AuthStorage(context)
        val (blogUrl, _, _) = authStorage.getCredentials()

        var baseUrl = if (blogUrl.isNullOrBlank()) {
            "https://placeholder.com/"
        } else {
            blogUrl.trim().removeSuffix("/") + "/"
        }

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://$baseUrl"
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val authHeader = authStorage.getAuthHeader()

                val request = chain.request().newBuilder()
                    .addHeader("Authorization", authHeader)
                    // Comprehensive multi-header workaround for LiteSpeed/Apache stripping
                    .addHeader("X-WP-Authorization", authHeader)
                    .addHeader("X-Authorization", authHeader)
                    .addHeader("X-Http-Authorization", authHeader)
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "SocialNotesPoster/1.1.4")
                    .build()
                chain.proceed(request)
            }
            .build()

        gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, JsonDeserializer { json, _, _ ->
                try {
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    format.parse(json.asString)
                } catch (e: Exception) {
                    null
                }
            })
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
    @GET("wp-json/wp/v2/users/me")
    suspend fun getCurrentUser(): Response<Unit>

    @POST("wp-json/wp/v2/jetpack-social-note")
    suspend fun postNote(@Body note: SocialNoteRequest): Response<SocialNoteResponse>

    // Added cache-buster parameter '_' to force fresh data from the server
    @GET("wp-json/wp/v2/jetpack-social-note")
    suspend fun getDrafts(
        @Query("status") status: String = "draft",
        @Query("context") context: String = "edit",
        @Query("per_page") perPage: Int = 100,
        @Query("_") cb: Long
    ): Response<List<SocialNoteResponse>>

    // Added status="draft" to prevent false 404s when verifying a single draft
    @GET("wp-json/wp/v2/jetpack-social-note/{id}")
    suspend fun getNote(
        @Path("id") id: Int,
        @Query("status") status: String = "draft",
        @Query("context") context: String = "edit"
    ): Response<SocialNoteResponse>

    @POST("wp-json/wp/v2/jetpack-social-note/{id}")
    suspend fun updateNote(@Path("id") id: Int, @Body note: SocialNoteRequest): Response<SocialNoteResponse>

    @DELETE("wp-json/wp/v2/jetpack-social-note/{id}")
    suspend fun deleteNote(@Path("id") id: Int, @Query("force") force: Boolean = true): Response<Unit>
}

@Keep
data class SocialNoteRequest(
    @SerializedName("content") val content: String,
    @SerializedName("status") val status: String
)

@Keep
data class SocialNoteResponse(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("content") val content: NoteContent? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("modified_gmt") val modified_gmt: Date? = null
)

@Keep
data class NoteContent(
    @SerializedName("raw") val raw: String? = null,
    @SerializedName("rendered") val rendered: String? = null
)
