package com.flipper.psadecrypt.remotecontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.flipper.psadecrypt.MainActivity
import com.flipper.psadecrypt.R
import kotlinx.coroutines.*

class RemoteControlFragment : Fragment() {

    private lateinit var imgScreen: ImageView
    private lateinit var txtNotConnected: TextView
    private lateinit var screenContainer: View
    private lateinit var controlsContainer: View

    private val handler = Handler(Looper.getMainLooper())
    private val decoder = ScreenDecoder()
    private var screenApi: FlipperScreenApi? = null
    private var streamScope: CoroutineScope? = null
    private var isStreaming = false

    private companion object {
        const val LONG_PRESS_THRESHOLD_MS = 500L
    }

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_remote_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imgScreen = view.findViewById(R.id.img_screen)
        txtNotConnected = view.findViewById(R.id.txt_not_connected)
        screenContainer = view.findViewById(R.id.screen_container)
        controlsContainer = view.findViewById(R.id.controls_container)

        setupButton(view.findViewById(R.id.btn_up), FlipperScreenApi.KEY_UP)
        setupButton(view.findViewById(R.id.btn_down), FlipperScreenApi.KEY_DOWN)
        setupButton(view.findViewById(R.id.btn_left), FlipperScreenApi.KEY_LEFT)
        setupButton(view.findViewById(R.id.btn_right), FlipperScreenApi.KEY_RIGHT)
        setupButton(view.findViewById(R.id.btn_ok), FlipperScreenApi.KEY_OK)
        setupButton(view.findViewById(R.id.btn_back), FlipperScreenApi.KEY_BACK)

        val rpcClient = mainActivity?.rpcClient
        if (rpcClient != null) {
            onConnectionChanged(true)
        } else {
            onConnectionChanged(false)
        }
    }

    private fun setupButton(button: Button, key: Int) {
        var longPressRunnable: Runnable? = null
        var wasLongPress = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wasLongPress = false
                    longPressRunnable = Runnable {
                        wasLongPress = true
                        sendButtonEvent(key, isLongPress = true)
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (!wasLongPress && event.action == MotionEvent.ACTION_UP) {
                        sendButtonEvent(key, isLongPress = false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun sendButtonEvent(key: Int, isLongPress: Boolean) {
        val api = screenApi ?: return
        val scope = streamScope ?: return
        scope.launch {
            try {
                if (isLongPress) {
                    api.longPressButton(key)
                } else {
                    api.pressButton(key)
                }
            } catch (e: Exception) {
                mainActivity?.appendLog("Input send failed: ${e.message}")
            }
        }
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!isAdded) return
        handler.post {
            if (connected) {
                txtNotConnected.visibility = View.GONE
                screenContainer.visibility = View.VISIBLE
                controlsContainer.visibility = View.VISIBLE
                startStreaming()
            } else {
                stopStreaming()
                txtNotConnected.visibility = View.VISIBLE
                screenContainer.visibility = View.GONE
                controlsContainer.visibility = View.GONE
            }
        }
    }

    private fun startStreaming() {
        val rpcClient = mainActivity?.rpcClient ?: return
        if (isStreaming) return

        val api = FlipperScreenApi(rpcClient)
        screenApi = api

        rpcClient.screenFrameListener = { frame ->
            val bitmap = decoder.decode(frame)
            if (bitmap != null) {
                handler.post {
                    if (isAdded) {
                        imgScreen.setImageBitmap(bitmap)
                    }
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        streamScope = scope
        isStreaming = true

        scope.launch {
            val result = api.startStream()
            if (result.isFailure) {
                mainActivity?.appendLog("Failed to start screen stream: ${result.exceptionOrNull()?.message}")
            } else {
                mainActivity?.appendLog("Screen streaming started")
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false

        val api = screenApi
        val scope = streamScope

        mainActivity?.rpcClient?.screenFrameListener = null

        scope?.launch {
            try {
                api?.stopStream()
                mainActivity?.appendLog("Screen streaming stopped")
            } catch (_: Exception) {}
        }

        scope?.cancel()
        streamScope = null
        screenApi = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStreaming()
    }
}
