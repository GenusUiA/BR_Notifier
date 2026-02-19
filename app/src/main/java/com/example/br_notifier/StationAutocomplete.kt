package com.example.br_notifier

import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

// ------------------- DTO -------------------
data class StationDto(
    val prefix: String,
    val label: String,
    val label_tail: String?,
    val value: String,
    val gid: String,
    val lon: Double,
    val lat: Double,
    val exp: String?,
    val ecp: String?,
    val otd: String?
)

// ------------------- Retrofit API -------------------
// Используем обычный Retrofit без Moshi, просто для структуры
interface StationApi {
    @GET("ru/ajax/autocomplete/search")
    suspend fun searchStations(@Query("term") term: String): String // вернем JSON как строку
}

// ------------------- API Client -------------------
object ApiClient {
    private val client = OkHttpClient()

    val stationApi = object : StationApi {
        override suspend fun searchStations(term: String): String = withContext(Dispatchers.IO) {
            val url = "https://pass.rw.by/ru/ajax/autocomplete/search?term=$term"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Ошибка сети: ${response.code}")
                response.body?.string() ?: "[]"
            }
        }
    }
}

// ------------------- AutoComplete Setup -------------------
fun setupStationAutocomplete(
    autoCompleteTextView: AutoCompleteTextView,
    scope: LifecycleCoroutineScope
) {
    autoCompleteTextView.threshold = 3

    autoCompleteTextView.addTextChangedListener(object : TextWatcher {
        private var job: Job? = null

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable?) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val query = s.toString()
            if (query.length >= 3) {
                job?.cancel()
                job = scope.launch {
                    try {
                        val json = ApiClient.stationApi.searchStations(query)
                        val gson = Gson()
                        val type = object : TypeToken<List<StationDto>>() {}.type
                        val stations: List<StationDto> = gson.fromJson(json, type)
                        val names = stations.map { it.label }

                        withContext(Dispatchers.Main) {
                            val adapter = ArrayAdapter(
                                autoCompleteTextView.context,
                                android.R.layout.simple_dropdown_item_1line,
                                names
                            )
                            autoCompleteTextView.setAdapter(adapter)
                            adapter.notifyDataSetChanged()
                            autoCompleteTextView.showDropDown()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    })
}
