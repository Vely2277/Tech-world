package com.example.newconstructionappwithlocationtracking.api

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

object Config {
    private var properties: Properties? = null

    fun init(context: Context) {
        if (properties == null) {
            properties = Properties()
            try {
                val inputStream = context.assets.open(".env")
                properties?.load(inputStream)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getProperty(key: String, defaultValue: String = ""): String {
        return properties?.getProperty(key) ?: defaultValue
    }

    val BACKEND_URL: String
        get() = getProperty("BACKEND_URL", "https://real-pakistan-backend.onrender.com")
}
