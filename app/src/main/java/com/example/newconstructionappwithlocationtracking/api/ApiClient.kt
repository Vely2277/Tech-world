package com.example.newconstructionappwithlocationtracking.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Properties
import android.content.Context

object ApiClient {
    private var retrofit: Retrofit? = null

    fun getClient(context: Context): Retrofit {
        if (retrofit == null) {
            val baseUrl = getBackendUrl(context)
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    private fun getBackendUrl(context: Context): String {
        return try {
            val inputStream = context.assets.open(".env")
            val properties = Properties()
            properties.load(inputStream)
            properties.getProperty("BACKEND_URL", "https://real-pakistan-backend.onrender.com")
        } catch (e: Exception) {
            "https://real-pakistan-backend.onrender.com"
        }
    }
}
