package com.mechrobotix.aprendels

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.location.LocationManager
import java.util.Locale
import java.util.concurrent.Executors

/** Dos flujos BLE de diagnostico. Crear este panel en onCreate, antes de STARTED.
 * Registra sus propios permisos BLE; se conserva requestPermission por compatibilidad.
 * No aprueba señas ni modifica la camara. */
@SuppressLint("MissingPermission")
class Bmi160Panel(private val activity:ComponentActivity,@Suppress("UNUSED_PARAMETER") requestPermission:()->Unit) {
    val button=AppUi.style(Button(activity)).apply {
        text="Sensores · izquierda y derecha";textSize=12f
        setOnClickListener{open()}
    }
    private class HandUi {
        val calibration=Bmi160Calibration()
        var epoch=-1L
        var lastSample:Bmi160Sample?=null
        var readings:TextView?=null
        var connect:Button?=null
        var calibrate:Button?=null
        var disconnect:Button?=null
        var connecting=false
    }
    private val hands=SensorHand.values().associateWith{HandUi()}
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newFixedThreadPool(2)
    private var dialog:AlertDialog?=null
    private var chooser:AlertDialog?=null
    private var pendingHand:SensorHand?=null
    private var inline=false
    private var active=false
    private var destroyed=false
    private var stopScan:(()->Unit)?=null
    private var scanTimeout:Runnable?=null
    private val permissionLauncher=activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionResult(Esp32BluetoothClient.hasBlePermissions(activity)) }
    private var drawAt=0L
    private val poll=object:Runnable {
        override fun run() {
            if(!active)return
            val now=SystemClock.elapsedRealtime()
            for(hand in SensorHand.values()) {
                val ui=hands.getValue(hand);val state=Esp32BluetoothClient.state(hand);val s=state.sample
                if(ui.epoch!=state.epoch){ui.epoch=state.epoch;ui.calibration.reset();ui.lastSample=null}
                if(s==null || now-s.receivedMs>500) {
                    if(ui.calibration.running || ui.calibration.ready)ui.calibration.reset()
                } else if(s!==ui.lastSample){ui.calibration.add(s);ui.lastSample=s}
            }
            if(now-drawAt>=250){drawAt=now;draw(now)}
            main.postDelayed(this,20)
        }
    }
    fun resume(){
        active=true;main.removeCallbacks(poll);main.post(poll)
        pendingHand?.let {hand->pendingHand=null;main.post{if(active)chooseDevice(hand)}}
    }
    fun pause(){
        active=false;main.removeCallbacks(poll);stopDiscovery();chooser?.dismiss();chooser=null
        dialog?.dismiss();dialog=null
        hands.values.forEach{if(!inline) it.readings=null;it.calibration.reset();it.lastSample=null}
        // Explicit disconnect buttons own the sockets, not an Activity's lifecycle.
    }
    fun destroy(){destroyed=true;pause();worker.shutdown()}
    fun permissionResult(granted:Boolean){
        if(granted){
            val hand=pendingHand
            if(active && hand!=null){pendingHand=null;chooseDevice(hand)}
        }else{
            pendingHand=null
            Toast.makeText(activity,"Permite Dispositivos cercanos para conectar los sensores",Toast.LENGTH_LONG).show()
        }
    }
    private fun requestFor(hand:SensorHand){
        pendingHand=hand
        permissionLauncher.launch(Esp32BluetoothClient.requiredPermissions())
    }
    private fun stopDiscovery(){
        scanTimeout?.let{main.removeCallbacks(it)};scanTimeout=null
        stopScan?.invoke();stopScan=null
    }
    fun attachInline(parent:LinearLayout) { inline=true;parent.addView(buildBody());draw(SystemClock.elapsedRealtime()) }
    private fun open(){
        if(dialog?.isShowing==true)return
        dialog=AlertDialog.Builder(activity).setTitle("Dos ESP32 · BMI160 BLE")
            .setView(ScrollView(activity).apply { addView(buildBody()) }).setPositiveButton("Volver",null).create().also {
                it.setOnDismissListener { if(!inline) hands.values.forEach { ui->ui.readings=null;ui.connect=null;ui.calibrate=null;ui.disconnect=null };dialog=null }
                it.show()
            }
        draw(SystemClock.elapsedRealtime())
    }
    private fun buildBody():LinearLayout {
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12);setBackgroundColor(androidx.core.content.ContextCompat.getColor(activity,R.color.surface))}
        fun label(text:String,size:Float=14f)=TextView(activity).apply{this.text=text;textSize=size;setTextColor(androidx.core.content.ContextCompat.getColor(activity,R.color.text_dark))}
        fun action(title:String,click:()->Unit):Button=AppUi.style(Button(activity)).apply {
            text=title;isAllCaps=false;setOnClickListener{click()};body.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=AppUi.dp(activity,8) })
        }
        body.addView(label("Asigna un ESP32 diferente a cada mano. Puedes usar uno o ambos; las lecturas se mantienen separadas. Busca los C3 aquí; no requieren emparejamiento en Ajustes."))
        action("Activar Bluetooth / ajustes"){activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}
        for(hand in SensorHand.values()) {
            val ui=hands.getValue(hand)
            body.addView(label(hand.label,19f).apply{setTextColor(androidx.core.content.ContextCompat.getColor(activity,R.color.brand));setPadding(0,24,0,8)})
            ui.readings=label("").also{body.addView(it)}
            ui.connect=action("Conectar / cambiar · ${hand.label}"){
                if(Esp32BluetoothClient.hasBlePermissions(activity))chooseDevice(hand) else requestFor(hand)
            }
            ui.calibrate=action("Calibrar cero · ${hand.label}"){
                val state=Esp32BluetoothClient.state(hand);val s=state.sample
                if(state.connected && s!=null && SystemClock.elapsedRealtime()-s.receivedMs<=500){
                    // Set epoch before starting, otherwise the next poll would clear this calibration.
                    ui.epoch=state.epoch;ui.lastSample=s;ui.calibration.start()
                }else Toast.makeText(activity,"Espera lecturas recientes de esta mano",Toast.LENGTH_LONG).show()
            }
            ui.disconnect=action("Desconectar · ${hand.label}"){
                Esp32BluetoothClient.disconnect(hand);ui.calibration.reset();draw(SystemClock.elapsedRealtime())
            }
        }
        action("Desconectar ambos"){
            Esp32BluetoothClient.disconnect();hands.values.forEach{it.calibration.reset()};draw(SystemClock.elapsedRealtime())
        }
        body.addView(label("A: aceleración en g. G: velocidad de giro en °/s, no ángulo.\n"+
            "Las lecturas complementan la observación. La aprobación de la seña se calcula con la cámara.\n"+
            "La calibración se reinicia al salir de esta pantalla o perder datos. Las conexiones permanecen hasta desconectarlas o cerrar el proceso."))
        return body
    }
    private fun chooseDevice(hand:SensorHand){
        val ui=hands.getValue(hand)
        if(!active || destroyed || ui.connecting || Esp32BluetoothClient.state(hand).connecting)return
        if(!Esp32BluetoothClient.hasBlePermissions(activity)){requestFor(hand);return}
        if(!Esp32BluetoothClient.isBluetoothEnabled(activity)){
            Toast.makeText(activity,"Activa Bluetooth y vuelve a buscar desde este panel",Toast.LENGTH_LONG).show();return
        }
        // Android 6–11 puede exigir ubicacion encendida para entregar resultados BLE.
        if(Build.VERSION.SDK_INT in 23..30){
            val lm=activity.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
            val enabled=try{lm?.isProviderEnabled(LocationManager.GPS_PROVIDER)==true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER)==true}catch(_:Exception){false}
            if(!enabled){
                Toast.makeText(activity,"Activa Ubicación para buscar BLE en Android 6–11",Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return
            }
        }
        chooser?.dismiss();stopDiscovery()
        val devices=mutableListOf<PairedBluetoothDevice>()
        val rows=ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,mutableListOf())
        val prefs=activity.getSharedPreferences("bmi_devices_ble",0)
        val previous=prefs.getString(hand.name,null)
        val other=SensorHand.values().first{it!=hand}
        val title="Asignar ${hand.label.lowercase()}"
        val selection=AlertDialog.Builder(activity).setTitle("$title · buscando…")
            .setAdapter(rows){_,which->
                if(active && !ui.connecting && which in devices.indices){
                    val chosen=devices[which]
                    stopDiscovery();ui.connecting=true;ui.calibration.reset()
                    val token=Esp32BluetoothClient.connectionToken(hand)
                    val context=activity.applicationContext
                    draw(SystemClock.elapsedRealtime())
                    worker.execute {
                        var failure:String?=null
                        try {
                            Esp32BluetoothClient.connectIfCurrent(context,chosen,token,hand)
                            prefs.edit().putString(hand.name,chosen.address).apply()
                        }catch(e:Exception){failure=e.message ?: "Error de conexión"}
                        main.post {
                            ui.connecting=false
                            if(active && !destroyed){
                                failure?.let{Toast.makeText(activity,it,Toast.LENGTH_LONG).show()}
                                draw(SystemClock.elapsedRealtime())
                            }
                        }
                    }
                }
            }.setNegativeButton("Cancelar",null).create()
        chooser=selection
        selection.setOnDismissListener {
            if(chooser===selection){stopDiscovery();chooser=null}
        }
        selection.show()
        try {
            stopScan=Esp32BluetoothClient.startScan(activity,{device->
                main.post {
                    if(active && chooser===selection && selection.isShowing &&
                        !device.address.equals(Esp32BluetoothClient.state(other).address,true) &&
                        devices.none{it.address==device.address}) {
                        devices.add(device)
                        rows.add("${device.name}\n${device.address}${if(device.address==previous) " · última selección" else ""}")
                        selection.setTitle("$title · ${devices.size} encontrado(s)")
                    }
                }
            },{message->main.post {
                if(chooser===selection){stopDiscovery();selection.setTitle("Búsqueda BLE detenida")
                    Toast.makeText(activity,message,Toast.LENGTH_LONG).show()}
            }})
            scanTimeout=Runnable {
                if(chooser===selection){
                    stopDiscovery()
                    selection.setTitle(if(devices.isEmpty())"No se encontraron sensores BLE" else title)
                    if(devices.isEmpty())Toast.makeText(activity,
                        "Enciende el C3 con el firmware nuevo, acércalo y vuelve a buscar. Si está conectado a otro teléfono, desconéctalo allí.",Toast.LENGTH_LONG).show()
                }
            }.also{main.postDelayed(it,8000)}
        }catch(_:SecurityException){selection.dismiss();requestFor(hand)}
        catch(e:Exception){selection.dismiss();Toast.makeText(activity,e.message ?: "Error BLE",Toast.LENGTH_LONG).show()}
    }
    private fun draw(now:Long){
        var connected=0;var freshCount=0
        for(hand in SensorHand.values()) {
            val ui=hands.getValue(hand);val c=Esp32BluetoothClient.state(hand);val s=c.sample
            val fresh=c.connected && s!=null && now-s.receivedMs<=500
            if(c.connected)connected++
            if(fresh)freshCount++
            ui.connect?.isEnabled=!ui.connecting && !c.connecting
            ui.calibrate?.isEnabled=fresh && !ui.connecting && !c.connecting
            ui.disconnect?.isEnabled=c.connected || c.connecting || ui.connecting
            val calibration=ui.calibration
            val values=if(s!=null && fresh)String.format(Locale.US,
                "A [g]: %.3f  %.3f  %.3f\n|A|: %.3f g\nG [°/s]: %.2f  %.2f  %.2f\nSecuencia: %d · ESP32: %d ms\nRecepción: hace %d ms\n",
                s.ax,s.ay,s.az,s.accelerationNorm(),s.gx-calibration.bx,s.gy-calibration.by,s.gz-calibration.bz,
                s.sequence,s.deviceMs,now-s.receivedMs)else "Sin mediciones recientes. No se muestran datos anteriores.\n"
            ui.readings?.text="${c.name ?: "Sin dispositivo"}\n${c.address ?: ""}\n${c.status}\n\n$values\n${calibration.message}\n"+
                "Recibidos: ${c.received} · saltos: ${c.missed} · inválidos: ${c.invalid}\n"
        }
        button.text="Sensores: $connected/2 conectados · $freshCount/2 con datos"
    }
}
