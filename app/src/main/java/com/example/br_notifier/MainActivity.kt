package com.example.br_notifier

import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup

class MainActivity : AppCompatActivity() {

    private lateinit var editFrom: EditText
    private lateinit var editTo: EditText
    private lateinit var editDate: EditText
    private lateinit var editTime: EditText
    private lateinit var editReload: EditText
    private lateinit var buttonStart: Button
    private lateinit var textStatus: TextView

    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        editFrom = findViewById(R.id.editFrom)
        editTo = findViewById(R.id.editTo)
        editDate = findViewById(R.id.editDate)
        editTime = findViewById(R.id.editTime)
        editReload = findViewById(R.id.editReload)
        buttonStart = findViewById(R.id.buttonStart)
        textStatus = findViewById(R.id.textStatus)

        buttonStart.setOnClickListener {
            startChecking()
        }
    }

    private fun startChecking() {
        val from = editFrom.text.toString()
        val to = editTo.text.toString()
        val date = editDate.text.toString()
        val targetTime = editTime.text.toString()
        val reloadSec = editReload.text.toString().toLongOrNull()?.coerceAtLeast(5L) ?: 5L

        textStatus.text = "Статус: проверка..."

        mainScope.launch {
            while (isActive) {
                checkTrains(from, to, date, targetTime)
                delay(reloadSec * 1000)
            }
        }
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
                        Handler(Looper.getMainLooper()).post {
                            textStatus.text = "Есть $seatsAvailable мест $date $departureTime $fromStation → $toStation"
                            playSound()
                        }
                        break
                    }
                }

                if (!found) {
                    Handler(Looper.getMainLooper()).post {
                        textStatus.text = "Нет мест на $targetTime"
                    }
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    textStatus.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun playSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}
