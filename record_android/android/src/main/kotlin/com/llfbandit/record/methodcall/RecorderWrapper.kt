package com.llfbandit.record.methodcall

import android.app.Activity
import android.content.ComponentName
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.llfbandit.record.record.recorder.AudioRecorder
import com.llfbandit.record.record.RecordConfig
import com.llfbandit.record.record.bluetooth.BluetoothScoListener
import com.llfbandit.record.record.recorder.IRecorder
import com.llfbandit.record.record.recorder.MediaRecorder
import com.llfbandit.record.record.stream.RecorderRecordStreamHandler
import com.llfbandit.record.record.stream.RecorderStateStreamHandler
import com.llfbandit.record.service.RecordingService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

internal class RecorderWrapper(
    private val context: Context,
    recorderId: String,
    messenger: BinaryMessenger
): BluetoothScoListener {
    companion object {
        const val EVENTS_STATE_CHANNEL = "com.llfbandit.record/events/"
        const val EVENTS_RECORD_CHANNEL = "com.llfbandit.record/eventsRecord/"
        private val TAG = RecorderWrapper::class.java.simpleName
    }

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private var currentActivity: Activity? = null

    private var eventChannel: EventChannel? = null
    // private val recorderStateStreamHandler = RecorderStateStreamHandler()
    private var eventRecordChannel: EventChannel? = null
    // private val recorderRecordStreamHandler = RecorderRecordStreamHandler()
    private var recorder: IRecorder? = null

    private var onServiceConnectedCallback: (() -> Unit)? = null

    private fun awaitServiceConnection(callback: () -> Unit) {
        if (serviceBound) {
            callback.invoke()
        } else {
            onServiceConnectedCallback = callback
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected")
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            serviceBound = true

            currentActivity?.let { recordingService?.setActivity(it) }

            // eventChannel = EventChannel(messenger, EVENTS_STATE_CHANNEL + recorderId)
            // eventRecordChannel = EventChannel(messenger, EVENTS_RECORD_CHANNEL + recorderId)

            // recordingService?.let { rService ->
            //     eventChannel?.setStreamHandler(rService.getRecorderStateStreamHandler())
            //     eventRecordChannel?.setStreamHandler(rService.getRecorderRecordStreamHandler())
            // }
            
            // Set up event channels with the service's stream handlers
           eventChannel?.setStreamHandler(recordingService?.getRecorderStateStreamHandler())
           eventRecordChannel?.setStreamHandler(recordingService?.getRecorderRecordStreamHandler())

            onServiceConnectedCallback?.invoke()
            onServiceConnectedCallback = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected")
            serviceBound = false
        }
    }

    init {
        Log.d(TAG, "onInit")
        eventChannel = EventChannel(messenger, EVENTS_STATE_CHANNEL + recorderId)
        eventRecordChannel = EventChannel(messenger, EVENTS_RECORD_CHANNEL + recorderId)

        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {}
            override fun onCancel(arguments: Any?) {}
        })
        eventRecordChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {}
            override fun onCancel(arguments: Any?) {}
        })

        bindService()
    }

    private fun bindService() {
        Log.d(TAG, "bindService")
        Intent(context, RecordingService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindService() {
        Log.d(TAG, "unbindService")
        if (serviceBound) {
            context.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    fun setActivity(activity: Activity?) {
        Log.d(TAG, "setActivity")
        currentActivity = activity
        recordingService?.setActivity(activity)
    }


    fun startRecordingToFile(config: RecordConfig, result: MethodChannel.Result) {
        // startRecording(config, result)
    }

    fun startRecordingToStream(config: RecordConfig, result: MethodChannel.Result) {
        Log.d(TAG, "startRecordingToStream")
        awaitServiceConnection {
            if (serviceBound) {
                Log.d(TAG, "startRecordingToStream - bound")
                recordingService?.startRecording(config)
                result.success(null)
            } else {
                Log.d(TAG, "startRecordingToStream - not bound")
                result.error("record", "Recording service not bound", null)
            }
        }
    }

    fun dispose() {
        try {
            recordingService?.stopRecording()
        } catch (ignored: Exception) {
        } finally {
            unbindService()
        }

        eventChannel?.setStreamHandler(null)
        eventChannel = null

        eventRecordChannel?.setStreamHandler(null)
        eventRecordChannel = null
    }

    fun stop(result: MethodChannel.Result) {
        try {
            recordingService?.stopRecording()
            result.success(null)
        } catch (e: Exception) {
            result.error("record", e.message, e.cause)
        }
    }

    fun pause(result: MethodChannel.Result) {
        try {
            recordingService?.pauseRecording()
            result.success(null)
        } catch (e: Exception) {
            result.error("record", e.message, e.cause)
        }
    }

    fun resume(result: MethodChannel.Result) {
        try {
            recordingService?.resumeRecording()
            result.success(null)
        } catch (e: Exception) {
            result.error("record", e.message, e.cause)
        }
    }

    fun cancel(result: MethodChannel.Result) {
        try {
            recordingService?.cancelRecording()
            result.success(null)
        } catch (e: Exception) {
            result.error("record", e.message, e.cause)
        }
    }

    fun isPaused(result: MethodChannel.Result) {
        result.success(recorder?.isPaused ?: false)
    }

    fun isRecording(result: MethodChannel.Result) {
        result.success(recorder?.isRecording ?: false)
    }

    fun getAmplitude(result: MethodChannel.Result) {
        if (recorder != null) {
            val amps = recorder!!.getAmplitude()
            val amp: MutableMap<String, Any> = HashMap()
            amp["current"] = amps[0]
            amp["max"] = amps[1]
            result.success(amp)
        } else {
            result.success(null)
        }
    }
//
//    private fun startRecording(config: RecordConfig, result: MethodChannel.Result) {
//        try {
//            if (recorder == null) {
//                recorder = createRecorder(config)
//                start(config, result)
//            } else if (recorder!!.isRecording) {
//                recorder!!.stop(fun(_) = start(config, result))
//            } else {
//                start(config, result)
//            }
//        } catch (e: Exception) {
//            result.error("record", e.message, e.cause)
//        }
//    }
//
//    private fun createRecorder(config: RecordConfig): IRecorder {
//        if (config.useLegacy) {
//            return MediaRecorder(context, recorderStateStreamHandler)
//        }
//
//        return AudioRecorder(
//            recorderStateStreamHandler,
//            recorderRecordStreamHandler,
//            context
//        )
//    }
//
//    private fun start(config: RecordConfig, result: MethodChannel.Result) {
//        recorder!!.start(config)
//        result.success(null)
//    }

    ///////////////////////////////////////////////////////////
    // BluetoothScoListener
    ///////////////////////////////////////////////////////////
    override fun onBlScoConnected() {
    }

    override fun onBlScoDisconnected() {
    }
}