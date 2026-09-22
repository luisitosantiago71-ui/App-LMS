package com.mechrobotix.aprendels

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.mechrobotix.aprendels.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pairedDevices: List<PairedBluetoothDevice> = emptyList()
    private var afterPermissionGranted: (() -> Unit)? = null

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = Esp32BluetoothClient.hasBluetoothConnectPermission(this)

            if (granted) {
                showStatus("Permiso Bluetooth concedido. Buscando dispositivos emparejados...")
                afterPermissionGranted?.invoke()
            } else {
                showStatus("Permiso Bluetooth denegado. La app no podrá conectarse al guante.")
            }

            afterPermissionGranted = null
            updateConnectionButtons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        updateConnectionButtons()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionButtons()
    }

    private fun setupButtons() {

        binding.btnLoadDevices.setOnClickListener {
            loadPairedDevices()
        }

        binding.btnPracticeCamera.setOnClickListener {
            startActivity(Intent(this, PracticeSignActivity::class.java))
        }

        binding.btnOpenBluetoothSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        binding.btnConnect.setOnClickListener {
            connectSelectedDevice()
        }

        binding.btnDisconnect.setOnClickListener {
            Esp32BluetoothClient.disconnect()
            showStatus("Guante desconectado.")
            updateConnectionButtons()
        }

        binding.btnAlphabet.setOnClickListener {
            startActivity(Intent(this, AlphabetActivity::class.java))
        }
    }

    private fun requireBluetoothPermission(onGranted: () -> Unit) {
        if (Esp32BluetoothClient.hasBluetoothConnectPermission(this)) {
            onGranted()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            afterPermissionGranted = onGranted

            bluetoothPermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            onGranted()
        }
    }

    private fun loadPairedDevices() {
        requireBluetoothPermission {
            if (!Esp32BluetoothClient.isBluetoothEnabled(this)) {
                showStatus("Bluetooth está apagado. Actívalo desde los ajustes Bluetooth del teléfono.")
                binding.btnOpenBluetoothSettings.visibility = View.VISIBLE
                return@requireBluetoothPermission
            }

            pairedDevices = Esp32BluetoothClient.listPairedDevices(this)

            if (pairedDevices.isEmpty()) {
                showStatus("No hay dispositivos emparejados. Empareja primero el guante desde los ajustes Bluetooth.")
                binding.btnOpenBluetoothSettings.visibility = View.VISIBLE
            } else {
                showStatus("Selecciona el guante en la lista.")
                binding.btnOpenBluetoothSettings.visibility = View.GONE
            }

            val labels = if (pairedDevices.isEmpty()) {
                listOf("No hay dispositivos emparejados")
            } else {
                pairedDevices.map { "${it.name} - ${it.address}" }
            }

            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                labels
            )

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            binding.spDevices.adapter = adapter
            updateConnectionButtons()
        }
    }

    private fun connectSelectedDevice() {
        requireBluetoothPermission {
            if (pairedDevices.isEmpty()) {
                showStatus("Primero busca el guante Bluetooth.")
                return@requireBluetoothPermission
            }

            val selectedIndex = binding.spDevices.selectedItemPosition
            val selectedDevice = pairedDevices.getOrNull(selectedIndex)

            if (selectedDevice == null) {
                showStatus("Selecciona un dispositivo válido.")
                return@requireBluetoothPermission
            }

            binding.btnConnect.isEnabled = false
            showStatus("Conectando con ${selectedDevice.name}...")

            thread {
                try {
                    Esp32BluetoothClient.connect(this, selectedDevice)

                    runOnUiThread {
                        showStatus("Conectado a ${selectedDevice.name}. Ya puedes ir al alfabeto.")
                        updateConnectionButtons()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        showStatus("No se pudo conectar. Verifica que el guante esté encendido y emparejado")

                        Toast.makeText(
                            this,
                            e.message ?: "Error de conexión",
                            Toast.LENGTH_LONG
                        ).show()

                        updateConnectionButtons()
                    }
                }
            }
        }
    }

    private fun updateConnectionButtons() {
        val connected = Esp32BluetoothClient.isConnected

        binding.btnDisconnect.isEnabled = connected
        binding.btnConnect.isEnabled = pairedDevices.isNotEmpty() && !connected

        if (connected) {
            val name = Esp32BluetoothClient.connectedName ?: "guante"
            binding.tvStatus.text = "Conectado a $name."
        }
    }

    private fun showStatus(message: String) {
        binding.tvStatus.text = message
    }
}