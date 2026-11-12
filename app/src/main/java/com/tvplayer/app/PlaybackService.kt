package com.tvplayer.app
import android.app.*
import android.content.Intent
import android.os.IBinder
class PlaybackService : Service() {
    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "playback")
            .setContentTitle("TV Player").setContentText("Идёт воспроизведение")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
