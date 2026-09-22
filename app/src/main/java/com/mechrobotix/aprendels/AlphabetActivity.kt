package com.mechrobotix.aprendels

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.mechrobotix.aprendels.databinding.ActivityAlphabetBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AlphabetActivity : ComponentActivity() {

    private lateinit var binding: ActivityAlphabetBinding
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewHandler = Handler(Looper.getMainLooper())
    private val hidePreview = Runnable {
        binding.letterPreview.visibility = View.GONE
    }
    private val letters = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "Ñ",
        "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    )
    private val letterImages = mapOf(
        "A" to R.drawable.sign_a,
        "B" to R.drawable.sign_b,
        "C" to R.drawable.sign_c,
        "D" to R.drawable.sign_d,
        "E" to R.drawable.sign_e,
        "F" to R.drawable.sign_f,
        "G" to R.drawable.sign_g,
        "H" to R.drawable.sign_h,
        "I" to R.drawable.sign_i,
        "J" to R.drawable.sign_j,
        "K" to R.drawable.sign_k,
        "L" to R.drawable.sign_l,
        "M" to R.drawable.sign_m,
        "N" to R.drawable.sign_n,
        "Ñ" to R.drawable.sign_enye,
        "O" to R.drawable.sign_o,
        "P" to R.drawable.sign_p,
        "Q" to R.drawable.sign_q,
        "R" to R.drawable.sign_r,
        "S" to R.drawable.sign_s,
        "T" to R.drawable.sign_t,
        "U" to R.drawable.sign_u,
        "V" to R.drawable.sign_v,
        "W" to R.drawable.sign_w,
        "X" to R.drawable.sign_x,
        "Y" to R.drawable.sign_y,
        "Z" to R.drawable.sign_z
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAlphabetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createAlphabetButtons()
        setupButtons()
        updateConnectionStatus()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
    }

    override fun onDestroy() {
        previewHandler.removeCallbacks(hidePreview)
        commandExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupButtons() {
        binding.btnRest.setOnClickListener {
            sendCommand('0', "Posición de reposo")
        }

        binding.btnBackConnection.setOnClickListener {
            finish()
        }

        binding.btnDisconnectFromAlphabet.setOnClickListener {
            Esp32BluetoothClient.disconnect()
            binding.tvLastCommand.text = "Guante desconectado."
            updateConnectionStatus()
        }

        binding.letterPreview.setOnClickListener {
            hideLetterPreview()
        }
    }

    private fun createAlphabetButtons() {
        binding.gridAlphabet.removeAllViews()

        letters.forEach { letter ->
            val button = Button(this).apply {
                text = letter
                textSize = 24f
                setAllCaps(false)
                setTextColor(
                    ContextCompat.getColor(
                        this@AlphabetActivity,
                        R.color.text_dark
                    )
                )
                background = ContextCompat.getDrawable(
                    this@AlphabetActivity,
                    R.drawable.bg_letter_button
                )
                setOnClickListener {
                    showLetterPreview(letter)
                    sendCommand(commandFor(letter), "Letra $letter")
                }
            }

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(68)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }

            binding.gridAlphabet.addView(button, params)
        }
    }

    private fun commandFor(letter: String): Char {
        return if (letter == "Ñ") 'N' else letter.first()
    }

    private fun showLetterPreview(letter: String) {
        val imageResource = letterImages[letter] ?: return

        binding.ivLetterSign.setImageResource(imageResource)
        binding.ivLetterSign.contentDescription = "Posición de la mano para la letra $letter"
        binding.tvPreviewLetter.text = "Letra $letter"
        binding.letterPreview.visibility = View.VISIBLE
        binding.letterPreview.bringToFront()

        previewHandler.removeCallbacks(hidePreview)
        previewHandler.postDelayed(hidePreview, 2000L)
    }

    private fun hideLetterPreview() {
        previewHandler.removeCallbacks(hidePreview)
        binding.letterPreview.visibility = View.GONE
    }

    private fun sendCommand(command: Char, label: String) {
        if (!Esp32BluetoothClient.isConnected) {
            binding.tvLastCommand.text =
                "Conecta primero el guante Bluetooth para enviar la señal."
            updateConnectionStatus()
            return
        }

        commandExecutor.execute {
            try {
                Esp32BluetoothClient.sendCommand(command)

                runOnUiThread {
                    binding.tvLastCommand.text = "$label enviado al guante."
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    binding.tvLastCommand.text =
                        "No se pudo enviar $label. ${exception.message ?: "Error de comunicación."}"
                    updateConnectionStatus()
                }
            }
        }
    }

    private fun updateConnectionStatus() {
        if (Esp32BluetoothClient.isConnected) {
            val name = Esp32BluetoothClient.connectedName ?: "guante"
            binding.tvConnectionStatus.text = "Estado: conectado a $name"
            binding.btnRest.isEnabled = true
        } else {
            binding.tvConnectionStatus.text = "Estado: sin conexión"
            binding.btnRest.isEnabled = false
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
