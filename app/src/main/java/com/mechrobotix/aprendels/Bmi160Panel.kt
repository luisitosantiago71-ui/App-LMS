package com.mechrobotix.aprendels

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.concurrent.Executors

/** Diagnostic UI only: never changes JPracticeEngine or enables success. */
@SuppressLint("MissingPermission")
class Bmi160Panel(private val activity:ComponentActivity,private val requestPermission:()->Unit) {
    val button=Button(activity).apply {
        text="Sensor BMI160 · diagnóstico";textSize=12f
        setOnClickListener {open()}
    }
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val calibration=Bmi160Calibration()
    private var dialog:AlertDialog?=null
    private var readings:TextView?=null
    private var pendingChoice=false
    private var active=false
    private var owned=false
    private var connecting=false
    private var epoch=-1L
    private var lastSample:Bmi160Sample?=null
    private var drawAt=0L
    private val poll=object:Runnable {
        override fun run() {
            if(!active)return
            val now=SystemClock.elapsedRealtime()
            val client=Esp32BluetoothClient
            val s=client.latestSample
            if(epoch!=client.streamEpoch){epoch=client.streamEpoch;calibration.reset();lastSample=null}
            if(s==null || now-s.receivedMs>500) {
                if(calibration.running || calibration.ready)calibration.reset()
            } else if(s!==lastSample) {calibration.add(s);lastSample=s}
            if(now-drawAt>=250){drawAt=now;draw(now)}
            main.postDelayed(this,20)
        }
    }
    fun resume(){
        active=true;main.removeCallbacks(poll);main.post(poll)
        if(pendingChoice){pendingChoice=false;main.post{if(active)chooseDevice()}}
    }
    fun pause(){
        active=false;main.removeCallbacks(poll);dialog?.dismiss();dialog=null;readings=null
        if(owned || connecting)Esp32BluetoothClient.disconnect()
        owned=false;connecting=false;calibration.reset();lastSample=null
    }
    fun destroy(){pause();worker.shutdownNow()}
    fun permissionResult(granted:Boolean){
        if(granted){
            pendingChoice=true
            if(active){pendingChoice=false;chooseDevice()}
        } else Toast.makeText(activity,"Permite Dispositivos cercanos para conectar el sensor",Toast.LENGTH_LONG).show()
    }
    private fun open(){
        if(dialog?.isShowing==true)return
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12);setBackgroundColor(Color.BLACK)}
        readings=TextView(activity).apply {setTextColor(Color.WHITE);textSize=14f}
        body.addView(readings)
        fun action(title:String,click:()->Unit){body.addView(Button(activity).apply{text=title;setOnClickListener{click()}})}
        action("Conectar ESP32 emparejado"){
            if(Esp32BluetoothClient.hasBluetoothConnectPermission(activity))chooseDevice() else requestPermission()
        }
        action("Ajustes Bluetooth / emparejar"){
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        action("Calibrar cero · dejar inmóvil"){
            val s=Esp32BluetoothClient.latestSample
            if(s!=null && SystemClock.elapsedRealtime()-s.receivedMs<=500){
                owned=true;calibration.start()
            }else Toast.makeText(activity,"Primero conecta y espera lecturas recientes",Toast.LENGTH_LONG).show()
        }
        action("Desconectar") {Esp32BluetoothClient.disconnect();calibration.reset();connecting=false}
        val scroll=ScrollView(activity).apply{addView(body)}
        dialog=AlertDialog.Builder(activity).setTitle("BMI160 · prueba de conexión")
            .setView(scroll).setPositiveButton("Volver a practicar",null).create().also {
                it.setOnDismissListener{readings=null;dialog=null};it.show()
            }
        draw(SystemClock.elapsedRealtime())
    }
    private fun chooseDevice(){
        if(!active || connecting)return
        try {
            if(!Esp32BluetoothClient.isBluetoothEnabled(activity)){
                Toast.makeText(activity,"Activa Bluetooth y empareja ESP32_Mano desde Ajustes",Toast.LENGTH_LONG).show();return
            }
            val devices=Esp32BluetoothClient.listPairedDevices(activity)
            if(devices.isEmpty()){
                Toast.makeText(activity,"Empareja primero ESP32_Mano en los ajustes del teléfono",Toast.LENGTH_LONG).show();return
            }
            AlertDialog.Builder(activity).setTitle("Selecciona el ESP32 del sensor")
                .setItems(devices.map{"${it.name}\n${it.address}"}.toTypedArray()){_,which->
                    if(active){
                        owned=true;connecting=true;calibration.reset()
                        val token=Esp32BluetoothClient.connectionToken()
                        worker.execute {
                            try{Esp32BluetoothClient.connectIfCurrent(activity,devices[which],token)}catch(_:Exception){}
                            main.post{connecting=false;if(active)draw(SystemClock.elapsedRealtime())}
                        }
                    }
                }.setNegativeButton("Cancelar",null).show()
        }catch(_:SecurityException){requestPermission()}
    }
    private fun draw(now:Long){
        val c=Esp32BluetoothClient;val s=c.latestSample
        val fresh=s!=null && now-s.receivedMs<=500
        button.text=when {
            !c.isConnected->"Sensor BMI160 · conectar / diagnóstico"
            !fresh->"Sensor BMI160 · sin datos recientes"
            calibration.ready->"BMI160 · recibiendo / calibrado"
            else->"BMI160 · recibiendo / sin calibrar"
        }
        val values=if(s!=null && fresh)String.format(Locale.US,
            "A [g]: %.3f  %.3f  %.3f\n|A|: %.3f g\nG [°/s]: %.2f  %.2f  %.2f\nSecuencia: %d · ESP32: %d ms\nÚltima recepción: hace %d ms\n",
            s.ax,s.ay,s.az,s.accelerationNorm(),s.gx-calibration.bx,s.gy-calibration.by,s.gz-calibration.bz,
            s.sequence,s.deviceMs,now-s.receivedMs) else "Sin mediciones recientes. No se usan valores anteriores.\n"
        readings?.text="${c.connectionStatus}\n\n$values\n${calibration.message}\n"+
            "Recibidos: ${c.receivedPackets} · saltos de secuencia: ${c.missedPackets} · inválidos: ${c.invalidPackets}\n\n"+
            "Orden de ejes: X, Y, Z. El giro mostrado es velocidad, no ángulo.\n"+
            "Diagnóstico: el sensor todavía no aprueba ni rechaza la J.\n"+
            "La calibración se pierde al salir o perder las lecturas."
    }
}
