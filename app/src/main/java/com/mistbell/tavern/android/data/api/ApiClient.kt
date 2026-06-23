package com.mistbell.tavern.android.data.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val PREFS = "mistbell_android"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_URL = "http://10.0.2.2:3000/"

    private var retrofit: Retrofit? = null
    private var api: TavernApi? = null

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun getApi(context: Context): TavernApi {
        if (api != null) return api!!

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = normalizeUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        api = retrofit!!.create(TavernApi::class.java)
        return api!!
    }

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return normalizeUrl(prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL)
    }

    fun setServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, normalizeUrl(url)).apply()
        // Reset retrofit to use new URL
        retrofit = null
        api = null
    }

    private fun normalizeUrl(url: String): String {
        var result = url.trim()
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://$result"
        }
        if (!result.endsWith("/")) result += "/"
        return result
    }
}
