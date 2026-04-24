package com.example.br_notifier

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import android.media.AudioManager
import android.media.ToneGenerator

class MonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var monitoringJob: Job? = null

    companion object {
        const val CHANNEL_ID = "TrainMonitoringChannel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val from = intent?.getStringExtra("from") ?: ""
        val to = intent?.getStringExtra("to") ?: ""
        val date = intent?.getStringExtra("date") ?: ""
        val targetTime = intent?.getStringExtra("time") ?: ""
        val interval = intent?.getLongExtra("interval", 30L) ?: 30L

        createNotificationChannel()
        val notification = createNotification("Мониторинг: $from -> $to")
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true

        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                if (isNetworkAvailable()) {
                    checkTrains(from, to, date, targetTime)
                } else {
                    NotificationHelper.showNotification(
                        this@MonitoringService, 
                        "Ошибка: потеряно интернет соединение",
                        99
                    )
                }
                delay(interval * 1000)
            }
        }

        return START_NOT_STICKY
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    private suspend fun checkTrains(from: String, to: String, date: String, targetTime: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "https://pass.rw.by/ru/route?from=$from&to=$to&date=$date"
                val doc = Jsoup.connect(url).get()
                val rows = doc.select(".sch-table__row-wrap.js-row")
                
                for (rowWrap in rows) {
                    val row = rowWrap.selectFirst(".sch-table__row") ?: continue
                    val departureTime = row.selectFirst(".train-from-time")?.text()?.trim() ?: ""
                    
                    if (departureTime == targetTime) {
                        val seatsText = row.selectFirst(".sch-table__tickets .sch-table__t-quant span")?.text() ?: ""
                        val seatsAvailable = seatsText.filter { it.isDigit() }.toIntOrNull() ?: 0

                        if (seatsAvailable > 0) {
                            NotificationHelper.showNotification(
                                this@MonitoringService,
                                "Билеты! $departureTime: мест - $seatsAvailable",
                                1
                            )
                            playSound()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playSound() {
        val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BR Нотификатор")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(pendingIntent)
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
        isRunning = false
        monitoringJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}