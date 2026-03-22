package com.example.br_notifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

object ScheduleRepository {

    private val client = OkHttpClient()

    suspend fun getTrainTimes(
        from: String,
        to: String,
        date: String
    ): List<String> = withContext(Dispatchers.IO) {

        val url =
            "https://pass.rw.by/ru/route/?from=$from&to=$to&date=$date"

        val request = Request.Builder()
            .url(url)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return@withContext emptyList()

        val doc = Jsoup.parse(html)

        val times = mutableListOf<String>()

        val elements = doc.select(".train-time")

        for (el in elements) {
            val time = el.text()
            if (time.matches(Regex("\\d{2}:\\d{2}"))) {
                times.add(time)
            }
        }

        times.distinct()
    }
}
