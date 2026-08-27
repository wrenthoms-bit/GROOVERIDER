package com.delrogue.grooverider.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.delrogue.grooverider.MainActivity
import com.delrogue.grooverider.R
import com.delrogue.grooverider.engine.GrooveriderEngine

/**
 * Keeps the audio engine alive across screen-off and app-switch.
 *
 * Without a foreground service the stream is killed the moment the app leaves
 * the foreground, which for an instrument reads as "it randomly stops working".
 */
class AudioEngineService : Service() {

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        startEngine()
        return START_STICKY
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- engine

    private fun startEngine() {
        if (!requestFocus()) {
            Log.w(TAG, "audio focus denied; starting anyway at the user's request")
        }
        GrooveriderEngine.create()
        if (!GrooveriderEngine.start()) {
            Log.e(TAG, "engine failed to start")
        }
    }

    private fun stopEngine() {
        GrooveriderEngine.stop()
        abandonFocus()
    }

    // ------------------------------------------------------------- focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                GrooveriderEngine.setToneEnabled(false)
                hasFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                GrooveriderEngine.setMasterGain(0.15f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                GrooveriderEngine.setMasterGain(0.8f)
                hasFocus = true
            }
        }
    }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }

    // ------------------------------------------------------------- notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio engine",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Grooverider audio engine running"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grooverider")
            .setContentText("Audio engine running")
            .setSmallIcon(R.drawable.ic_stat_engine)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "grvr.service"
        private const val CHANNEL_ID = "grooverider_engine"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.delrogue.grooverider.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AudioEngineService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AudioEngineService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
