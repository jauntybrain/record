package com.llfbandit.record.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.llfbandit.record.record.RecordConfig
import com.llfbandit.record.record.recorder.AudioRecorder
import com.llfbandit.record.record.stream.RecorderRecordStreamHandler
import com.llfbandit.record.record.stream.RecorderStateStreamHandler

class RecordingService : Service() {
    companion object {
        private val TAG = RecordingService::class.java.simpleName
        private const val NOTIFICATION_ID = 889
    }
    
    private val binder = RecordingBinder()
    private var audioRecorder: AudioRecorder? = null
    private lateinit var recorderStateStreamHandler: RecorderStateStreamHandler
    private lateinit var recorderRecordStreamHandler: RecorderRecordStreamHandler

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        recorderStateStreamHandler = RecorderStateStreamHandler()
        recorderRecordStreamHandler = RecorderRecordStreamHandler()
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }


    fun setActivity(activity: Activity?) {
        Log.d(TAG, "setActivity")
        recorderStateStreamHandler.setActivity(activity)
        recorderRecordStreamHandler.setActivity(activity)
    }

    fun startRecording(config: RecordConfig) {
        Log.d(TAG, "startRecording")
        audioRecorder = AudioRecorder(recorderStateStreamHandler, recorderRecordStreamHandler, applicationContext)
        audioRecorder?.start(config)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d(TAG, "startForeground")
            startForeground(NOTIFICATION_ID, createNotification(), FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    fun cancelRecording() {
        audioRecorder?.cancel()
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording")
        audioRecorder?.stop { _ ->
            stopForeground(true)
            stopSelf()
        }
    }

    fun pauseRecording() {
        audioRecorder?.pause()
    }

    fun resumeRecording() {
        audioRecorder?.resume()
    }

    fun getRecorderStateStreamHandler(): RecorderStateStreamHandler {
        return recorderStateStreamHandler
    }

    fun getRecorderRecordStreamHandler(): RecorderRecordStreamHandler {
        return recorderRecordStreamHandler
    }

    private fun createNotification(): Notification {
        Log.d(TAG, "createNotification")
        val channelId = "RecordingServiceChannel"
        val channelName = "Recording Service Channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ongoing call")
            .setContentText("EzDubs call is in progress")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}