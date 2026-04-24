package com.example.br_notifier

import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.Calendar
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.media.AudioManager
import android.media.ToneGenerator
import retrofit2.http.GET
import retrofit2.http.Query
import androidx.core.widget.doOnTextChanged
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import android.widget.AutoCompleteTextView
import androidx.lifecycle.lifecycleScope
import com.example.br_notifier.setupStationAutocomplete
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.br_notifier.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var editFrom: AutoCompleteTextView
    private lateinit var editTo: AutoCompleteTextView
    private lateinit var editDate: EditText
    private lateinit var editTime: EditText
    private lateinit var editReload: EditText
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button
    private lateinit var textStatus: TextView

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }

        editFrom = findViewById(R.id.editFrom)
        editTo = findViewById(R.id.editTo)
        editDate = findViewById(R.id.editDate)
        editTime = findViewById(R.id.editTime)
        editReload = findViewById(R.id.editReload)
        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        textStatus = findViewById(R.id.textStatus)

        setupStationAutocomplete(editFrom, lifecycleScope)
        setupStationAutocomplete(editTo, lifecycleScope)

        // Делаем кнопку неактивной по умолчанию
        buttonStart.isEnabled = false

        // Функция для проверки всех обязательных полей
        fun updateButtonState() {
            buttonStart.isEnabled =
                editFrom.text.isNotBlank() &&
                        editTo.text.isNotBlank() &&
                        editDate.text.isNotBlank() &&
                        editTime.text.isNotBlank()
        }

        // Добавляем отслеживание изменений в полях
        listOf(editFrom, editTo, editDate, editTime).forEach { editText ->
            editText.doOnTextChanged { _, _, _, _ -> updateButtonState() }
        }

        editDate.setOnClickListener { showDatePicker() }
        editTime.setOnClickListener { showTimePicker() }

        buttonStart.setOnClickListener { startChecking() }
        buttonStop.setOnClickListener { stopChecking() }
    }

    private fun startChecking() {
        val from = editFrom.text.toString()
        val to = editTo.text.toString()
        val date = editDate.text.toString()
        val targetTime = editTime.text.toString()
        val reloadSec = editReload.text.toString().toLongOrNull()?.coerceAtLeast(5L) ?: 5L

        textStatus.text = "Статус: проверка..."
        buttonStart.visibility = View.GONE
        buttonStop.visibility = View.VISIBLE

        searchJob?.cancel()
        searchJob = mainScope.launch {
            try {
                while (isActive) {
                    checkTrains(from, to, date, targetTime)
                    delay(reloadSec * 1000)
                }
            } finally {
                withContext(NonCancellable) {
                    buttonStart.visibility = View.VISIBLE
                    buttonStop.visibility = View.GONE
                }
            }
        }
    }

    private fun stopChecking() {
        searchJob?.cancel()
        textStatus.text = "Статус: остановлено"
    }

    private suspend fun checkTrains(from: String, to: String, date: String, targetTime: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "https://pass.rw.by/ru/route?from=$from&to=$to&date=$date"
                val doc = Jsoup.connect(url).get()

                val rows = doc.select(".sch-table__row-wrap.js-row")
                var found = false

                for (rowWrap in rows) {
                    val row = rowWrap.selectFirst(".sch-table__row") ?: continue
                    val departureTime = row.selectFirst(".train-from-time")?.text()?.trim() ?: continue
                    val fromStation = row.selectFirst(".train-from-name")?.text()?.trim() ?: ""
                    val toStation = row.selectFirst(".train-to-name")?.text()?.trim() ?: ""

                    val seatsText = row.selectFirst(".sch-table__tickets .sch-table__t-quant span")?.text() ?: "0"
                    val seatsAvailable = seatsText.filter { it.isDigit() }.toIntOrNull() ?: 0

                    if (departureTime == targetTime && seatsAvailable > 0) {
                        found = true
                        withContext(Dispatchers.Main) {
                            textStatus.text = "Есть $seatsAvailable мест $date $departureTime $fromStation → $toStation"
                            playSound()
                            NotificationHelper.showNotification(
                                this@MainActivity,
                                "Есть $seatsAvailable мест $fromStation → $toStation"
                            )
                        }
                        break
                    }
                }

                if (!found) {
                    withContext(Dispatchers.Main) {
                        textStatus.text = "Нет мест на $targetTime"
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textStatus.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun playSound() {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }


    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()

        val datePicker = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val formattedMonth = (month + 1).toString().padStart(2, '0')
                val formattedDay = dayOfMonth.toString().padStart(2, '0')
                editDate.setText("$year-$formattedMonth-$formattedDay")
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        datePicker.show()
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()

        val timePicker = TimePickerDialog(
            this,
            { _, hour, minute ->
                val formattedHour = hour.toString().padStart(2, '0')
                val formattedMinute = minute.toString().padStart(2, '0')
                editTime.setText("$formattedHour:$formattedMinute")
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )

        timePicker.show()
    }

    private fun AutoCompleteTextView.textChanges() = callbackFlow {
        val watcher = doOnTextChanged { text, _, _, _ ->
            trySend(text ?: "")
        }
        awaitClose { removeTextChangedListener(watcher) }
    }
}