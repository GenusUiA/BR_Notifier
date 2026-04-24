package com.example.br_notifier

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class MonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var monitoringJob: Job? = null

    companion object {
        const val CHANNEL_ID = "TrainMonitoringChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val from = intent?.getStringExtra("from") ?: ""
        val to = intent?.getStringExtra("to") ?: ""
        val date = intent?.getStringExtra("date") ?: ""
        val targetTime = intent?.getStringExtra("time") ?: ""
        val interval = intent?.getLongExtra("interval", 30L) ?: 30L

        createNotificationChannel()
        val notification = createNotification("Мониторинг: $from -> $to на $targetTime")
        startForeground(NOTIFICATION_ID, notification)

        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                checkTrains(from, to, date, targetTime)
                delay(interval * 1000)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun checkTrains(from: String, to: String, date: String, targetTime: String) {
        withContext(Dispatchers.IO) {
            try {
                val encodedFrom = URLEncoder.encode(from, "UTF-8")
                val encodedTo = URLEncoder.encode(to, "UTF-8")

                val url = "https://pass.rw.by/ru/route/?from=$encodedFrom&from_esr=&to=$encodedTo&front_date=&date=$date"
                
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .get()

                val rows = doc.select(".sch-table__row-wrap.js-row")
                var found = false

                for (rowWrap in rows) {
                    val row = rowWrap.selectFirst(".sch-table__row") ?: continue
                    val departureTime = row.selectFirst(".train-from-time")?.text()?.trim() ?: continue
                    
                    if (departureTime == targetTime) {
                        val seatsText = row.selectFirst(".sch-table__tickets .sch-table__t-quant span")?.text() ?: ""
                        val seatsAvailable = seatsText.filter { it.isDigit() }.toIntOrNull() ?: 0

                        if (seatsAvailable > 0) {
                            found = true
                            NotificationHelper.showNotification(
                                this@MonitoringService,
                                "БИЛЕТЫ! $departureTime: мест - $seatsAvailable"
                            )
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BR Notifier")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Train Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitoringJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
