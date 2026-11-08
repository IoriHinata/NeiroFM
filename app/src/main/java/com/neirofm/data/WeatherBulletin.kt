package com.neirofm.data

import android.content.Context

class WeatherBulletin(context: Context) {
    private val prefs = context.getSharedPreferences("neirofm_weather", Context.MODE_PRIVATE)
    fun save(city: String, forecast: String, savedAtMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putString("city", city).putString("forecast", forecast).putLong("saved_at", savedAtMillis).apply()
    }
    fun latest(): String? {
        val forecast = prefs.getString("forecast", null) ?: return null
        val city = prefs.getString("city", "ваш город") ?: "ваш город"
        return "Последний сохранённый прогноз для $city: $forecast"
    }
}
