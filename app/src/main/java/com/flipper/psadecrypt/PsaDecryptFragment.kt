package com.flipper.psadecrypt

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class PsaDecryptFragment : Fragment() {
    companion object {
        private const val TOTAL_16M = 0x1000000
    }

    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var speedText: TextView
    private lateinit var resultText: TextView
    private lateinit var testButton: Button
    private lateinit var runBf1Button: Button
    private lateinit var runBf2Button: Button
    private lateinit var runBothButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var w0Input: EditText
    private lateinit var w1Input: EditText

    private val handler = Handler(Looper.getMainLooper())
    var bfExecutor: BruteForceExecutor? = null
    private var progressRunnable: Runnable? = null
    private var bfStartTime = 0L

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_psa_decrypt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.status_text)
        progressText = view.findViewById(R.id.progress_text)
        speedText = view.findViewById(R.id.speed_text)
        resultText = view.findViewById(R.id.result_text)
        testButton = view.findViewById(R.id.test_button)
        runBf1Button = view.findViewById(R.id.run_bf1_button)
        runBf2Button = view.findViewById(R.id.run_bf2_button)
        runBothButton = view.findViewById(R.id.run_both_button)
        progressBar = view.findViewById(R.id.progress_bar)
        w0Input = view.findViewById(R.id.w0_input)
        w1Input = view.findViewById(R.id.w1_input)

        runBf1Button.setOnClickListener { runManualBf(1) }
        runBf2Button.setOnClickListener { runManualBf(2) }
        runBothButton.setOnClickListener { runManualBf(0) }
        testButton.setOnClickListener { runLocalBenchmark() }

        val cpuCount = Runtime.getRuntime().availableProcessors()
        statusText.text = "Ready — $cpuCount CPU cores available"
    }

    private fun parseHex(s: String): Int? {
        val clean = s.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return null
        return try {
            java.lang.Long.parseLong(clean, 16).toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun runManualBf(bfType: Int) {
        val w0 = parseHex(w0Input.text.toString())
        val w1 = parseHex(w1Input.text.toString())
        if (w0 == null || w1 == null) {
            statusText.text = "Enter valid hex values for w0 and w1"
            mainActivity?.appendLog("Invalid hex input: w0='${w0Input.text}' w1='${w1Input.text}'")
            return
        }

        mainActivity?.appendLog("Manual BF start: type=$bfType w0=0x${Integer.toHexString(w0).uppercase()} w1=0x${Integer.toHexString(w1).uppercase()}")
        setBfButtonsEnabled(false)

        if (bfType == 0) {
            runBfSequence(w0, w1)
        } else {
            val request = PsaBleProtocol.BfRequest(bfType, w0, w1)
            runBruteForce(request)
        }
    }

    fun runBfSequence(w0: Int, w1: Int) {
        statusText.text = "Running BF1 (16M keys)..."

        Thread {
            val executor1 = BruteForceExecutor()
            bfExecutor = executor1
            bfStartTime = System.currentTimeMillis()
            handler.post { startProgressPolling(executor1, TOTAL_16M) }

            mainActivity?.appendLog("BF1 started, range 0x23000000-0x24000000")
            val result1 = executor1.run(1, w0, w1, PsaBleProtocol.BF1_START, PsaBleProtocol.BF1_END)
            stopProgressPolling()
            mainActivity?.appendLog("BF1 done: found=${result1.found} elapsed=${result1.elapsedMs}ms")

            if (result1.found) {
                handler.post {
                    showResult(result1, 1)
                    setBfButtonsEnabled(true)
                }
                mainActivity?.sendBleData(PsaBleProtocol.encodeResult(result1))
                bfExecutor = null
                return@Thread
            }

            handler.post {
                statusText.text = "BF1 not found. Running BF2 (16M keys)..."
                progressBar.progress = 0
            }

            val executor2 = BruteForceExecutor()
            bfExecutor = executor2
            bfStartTime = System.currentTimeMillis()
            handler.post { startProgressPolling(executor2, TOTAL_16M) }

            mainActivity?.appendLog("BF2 started, range 0xF3000000-0xF4000000")
            val result2 = executor2.run(2, w0, w1, PsaBleProtocol.BF2_START, PsaBleProtocol.BF2_END)
            stopProgressPolling()
            mainActivity?.appendLog("BF2 done: found=${result2.found} elapsed=${result2.elapsedMs}ms")

            handler.post {
                if (result2.found) {
                    showResult(result2, 2)
                } else {
                    statusText.text = "Not found in BF1 or BF2"
                    resultText.text = ""
                    progressBar.progress = 100
                }
                setBfButtonsEnabled(true)
            }
            mainActivity?.sendBleData(PsaBleProtocol.encodeResult(result2))
            bfExecutor = null
        }.start()
    }

    fun runBruteForce(request: PsaBleProtocol.BfRequest) {
        val rangeStart: Int
        val rangeEnd: Int
        when (request.bfType) {
            1 -> { rangeStart = PsaBleProtocol.BF1_START; rangeEnd = PsaBleProtocol.BF1_END }
            2 -> { rangeStart = PsaBleProtocol.BF2_START; rangeEnd = PsaBleProtocol.BF2_END }
            else -> { statusText.text = "Unknown BF type: ${request.bfType}"; return }
        }

        statusText.text = "Running BF${request.bfType} on ${Runtime.getRuntime().availableProcessors()} cores..."
        progressBar.progress = 0
        resultText.text = ""
        setBfButtonsEnabled(false)

        val executor = BruteForceExecutor()
        bfExecutor = executor
        bfStartTime = System.currentTimeMillis()
        startProgressPolling(executor, TOTAL_16M)

        Thread {
            mainActivity?.appendLog("BF${request.bfType} started")
            val result = executor.run(request.bfType, request.w0, request.w1, rangeStart, rangeEnd)
            stopProgressPolling()
            mainActivity?.appendLog("BF${request.bfType} done: found=${result.found} elapsed=${result.elapsedMs}ms")

            handler.post {
                showResult(result, request.bfType)
                setBfButtonsEnabled(true)
            }

            mainActivity?.sendBleData(PsaBleProtocol.encodeResult(result))
            mainActivity?.appendLog("Result sent to Flipper over BLE")
            bfExecutor = null
        }.start()
    }

    fun handleBleData(data: ByteArray) {
        if (PsaBleProtocol.isCancelMessage(data)) {
            bfExecutor?.cancel()
            statusText.text = "BF cancelled by Flipper"
            mainActivity?.appendLog("BF cancel received")
            return
        }
        val request = PsaBleProtocol.parseBfRequest(data) ?: return
        mainActivity?.appendLog("BF request: type=${request.bfType} w0=0x${Integer.toHexString(request.w0).uppercase()} w1=0x${Integer.toHexString(request.w1).uppercase()}")
        w0Input.setText(Integer.toHexString(request.w0).uppercase())
        w1Input.setText(Integer.toHexString(request.w1).uppercase())
        if (request.bfType == 0) {
            setBfButtonsEnabled(false)
            runBfSequence(request.w0, request.w1)
        } else {
            runBruteForce(request)
        }
    }

    private fun setBfButtonsEnabled(enabled: Boolean) {
        handler.post {
            runBf1Button.isEnabled = enabled
            runBf2Button.isEnabled = enabled
            runBothButton.isEnabled = enabled
            testButton.isEnabled = enabled
        }
    }

    private fun showResult(result: BfResult, bfType: Int) {
        if (result.found) {
            statusText.text = "FOUND in BF$bfType — ${result.elapsedMs}ms!"
            resultText.text = "Counter: 0x${Integer.toHexString(result.counter).uppercase()}\n" +
                "V0: 0x${Integer.toHexString(result.decV0).uppercase()}\n" +
                "V1: 0x${Integer.toHexString(result.decV1).uppercase()}\n" +
                "BF type: $bfType\n" +
                "Time: ${result.elapsedMs}ms"
            mainActivity?.appendLog("FOUND! counter=0x${Integer.toHexString(result.counter).uppercase()}")
        } else {
            statusText.text = "Not found in BF$bfType — ${result.elapsedMs}ms"
            resultText.text = ""
        }
        progressBar.progress = 100
    }

    private fun runLocalBenchmark() {
        statusText.text = "Benchmarking BF1 (1M keys)..."
        progressBar.progress = 0
        resultText.text = ""
        setBfButtonsEnabled(false)
        mainActivity?.appendLog("Benchmark starting (1M keys, BF1)")

        val executor = BruteForceExecutor()
        bfExecutor = executor
        bfStartTime = System.currentTimeMillis()
        startProgressPolling(executor, 0x100000)

        Thread {
            val benchEnd = PsaBleProtocol.BF1_START + 0x100000
            val result = executor.run(1, 0, 0, PsaBleProtocol.BF1_START, benchEnd)
            stopProgressPolling()

            val elapsed = result.elapsedMs.coerceAtLeast(1)
            val keysPerSec = 0x100000L * 1000 / elapsed
            mainActivity?.appendLog("Benchmark done: ${keysPerSec * 1000} keys/sec in ${elapsed}ms")

            handler.post {
                val cores = Runtime.getRuntime().availableProcessors()
                statusText.text = "Benchmark: ${formatCount(keysPerSec.toInt())} keys/sec (${elapsed}ms)"
                progressBar.progress = 100
                val fullKeysPerSec = (keysPerSec * 1000).coerceAtLeast(1)
                resultText.text = "$cores cores, ${String.format("%,d", fullKeysPerSec)} keys/sec\n" +
                    "Full BF1 (16M) ETA: ${16_000_000L / fullKeysPerSec}s\n" +
                    "Full BF2 (16M) ETA: ${16_000_000L / (fullKeysPerSec * 3).coerceAtLeast(1)}s"
                setBfButtonsEnabled(true)
            }
            bfExecutor = null
        }.start()
    }

    private fun startProgressPolling(executor: BruteForceExecutor, totalKeys: Int) {
        val runnable = object : Runnable {
            override fun run() {
                val tested = executor.getTotalKeysTested()
                val elapsed = (System.currentTimeMillis() - bfStartTime).coerceAtLeast(1)
                val kps = tested * 1000L / elapsed
                val pct = if (totalKeys > 0) (tested.toLong() * 100 / totalKeys).toInt().coerceIn(0, 100) else 0

                progressBar.progress = pct
                progressText.text = "$pct% — ${formatCount(tested)} / ${formatCount(totalKeys)}"
                speedText.text = "${formatCount(kps.toInt())} keys/sec"

                mainActivity?.sendBleData(PsaBleProtocol.encodeProgress(tested, kps.toInt()))
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = runnable
        handler.postDelayed(runnable, 500)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun formatCount(n: Int): String {
        val v = n.toLong() and 0xFFFFFFFFL
        return when {
            v >= 1_000_000 -> "${v / 1_000_000}.${(v % 1_000_000) / 100_000}M"
            v >= 1_000 -> "${v / 1_000}K"
            else -> "$v"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bfExecutor?.cancel()
        stopProgressPolling()
    }
}
