package com.mechrobotix.aprendels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Se conserva el nombre del tipo para los puntos de llamada existentes.
// En BLE el dispositivo descubierto no necesita estar emparejado.
data class PairedBluetoothDevice(val name:String,val address:String,val device:BluetoothDevice)
enum class SensorHand(val label:String) { LEFT("Mano izquierda"), RIGHT("Mano derecha") }
data class BmiConnectionState(
    val connected:Boolean, val connecting:Boolean, val name:String?, val address:String?,
    val sample:Bmi160Sample?, val received:Long, val missed:Long, val invalid:Long,
    val epoch:Long, val status:String
)

/** Dos sesiones GATT independientes; conectar desde un worker, nunca desde el hilo UI.
 * Servicio propio BIN20 v1. No es compatible con el firmware SPP antiguo.
 * Las conexiones duran hasta desconectar, perder enlace o morir el proceso.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object Esp32BluetoothClient {
    val serviceUuid:UUID=UUID.fromString("920b0001-4e8d-4f7f-9e51-50b7c41a1600")
    private val dataUuid=UUID.fromString("920b0002-4e8d-4f7f-9e51-50b7c41a1600")
    private val cccdUuid=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val lock=Any()
    private class Session {
        var gatt:BluetoothGatt?=null
        var generation=0
        var connected=false; var connecting=false
        var name:String?=null; var address:String?=null
        var latest:Bmi160Sample?=null
        var received=0L; var missed=0L; var invalid=0L; var epoch=0L
        var status="Desconectado"
        var waiter:CountDownLatch?=null
    }
    private val sessions=SensorHand.values().associateWith { Session() }
    fun state(hand:SensorHand):BmiConnectionState=synchronized(lock) {
        val s=sessions.getValue(hand)
        BmiConnectionState(s.connected,s.connecting,s.name,s.address,s.latest,
            s.received,s.missed,s.invalid,s.epoch,s.status)
    }
    val isConnected:Boolean get()=state(SensorHand.RIGHT).connected
    val connectedName:String? get()=state(SensorHand.RIGHT).name
    val latestSample:Bmi160Sample? get()=state(SensorHand.RIGHT).sample
    val receivedPackets:Long get()=state(SensorHand.RIGHT).received
    val missedPackets:Long get()=state(SensorHand.RIGHT).missed
    val invalidPackets:Long get()=state(SensorHand.RIGHT).invalid
    val streamEpoch:Long get()=state(SensorHand.RIGHT).epoch
    val connectionStatus:String get()=state(SensorHand.RIGHT).status
    fun requiredPermissions():Array<String> = when {
        Build.VERSION.SDK_INT>=31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT>=23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
    fun hasBlePermissions(context:Context)=requiredPermissions().all {
        ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED
    }
    fun hasBluetoothConnectPermission(context:Context)=Build.VERSION.SDK_INT<31 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    fun getBluetoothAdapter(context:Context):BluetoothAdapter?=
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    fun isBluetoothEnabled(context:Context)=hasBluetoothConnectPermission(context) && getBluetoothAdapter(context)?.isEnabled==true

    /** Compatibilidad de compilacion solamente. Usa startScan para descubrir los C3 sin bond. */
    fun listPairedDevices(context:Context):List<PairedBluetoothDevice> {
        if(!hasBluetoothConnectPermission(context))return emptyList()
        return getBluetoothAdapter(context)?.bondedDevices.orEmpty()
            .filter { it.type==BluetoothDevice.DEVICE_TYPE_LE || it.type==BluetoothDevice.DEVICE_TYPE_DUAL }
            .map { PairedBluetoothDevice(it.name ?: "BLE",it.address,it) }
    }
    /** El llamador detiene el escaneo al cerrar el selector o al cumplirse su plazo. */
    fun startScan(context:Context,onDevice:(PairedBluetoothDevice)->Unit,onError:(String)->Unit):()->Unit {
        if(!hasBlePermissions(context))throw SecurityException("Faltan permisos de búsqueda BLE")
        val scanner=getBluetoothAdapter(context)?.bluetoothLeScanner ?: throw IOException("Activa Bluetooth")
        val callback=object:ScanCallback() {
            override fun onScanResult(callbackType:Int,result:ScanResult) {
                try {
                    val d=result.device
                    onDevice(PairedBluetoothDevice(result.scanRecord?.deviceName ?: d.name ?: "LSM BLE",d.address,d))
                }catch(_:SecurityException){onError("Permiso Bluetooth revocado")}
            }
            override fun onBatchScanResults(results:MutableList<ScanResult>) {results.forEach{onScanResult(0,it)}}
            override fun onScanFailed(errorCode:Int){onError("No se pudo buscar BLE (código $errorCode). Espera unos segundos y vuelve a buscar.")}
        }
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
        return {try{scanner.stopScan(callback)}catch(_:Exception){}}
    }
    fun connectionToken(hand:SensorHand=SensorHand.RIGHT):Int=synchronized(lock){sessions.getValue(hand).generation}
    fun connect(context:Context,device:PairedBluetoothDevice,hand:SensorHand=SensorHand.RIGHT)=
        connectIfCurrent(context,device,connectionToken(hand),hand)
    fun connectIfCurrent(context:Context,device:PairedBluetoothDevice,expected:Int,hand:SensorHand=SensorHand.RIGHT) {
        if(!hasBluetoothConnectPermission(context))throw SecurityException("Falta permiso Bluetooth")
        val s=sessions.getValue(hand)
        val done=CountDownLatch(1)
        val token:Int
        synchronized(lock) {
            if(expected!=s.generation)throw IOException("Conexión cancelada")
            if(sessions.any{(h,v)->h!=hand && v.address.equals(device.address,true)})
                throw IOException("Ese sensor ya está asignado a la otra mano")
            closeLocked(s);s.generation++;token=s.generation
            s.name=device.name;s.address=device.address;s.latest=null
            s.received=0;s.missed=0;s.invalid=0;s.epoch++
            s.connecting=true;s.status="Conectando BLE…";s.waiter=done
            val callback=object:BluetoothGattCallback() {
                private fun current(g:BluetoothGatt)=s.generation==token && s.gatt===g
                private fun fail(g:BluetoothGatt,message:String) {
                    // Todos los callbacks que llaman aqui mantienen lock.
                    if(current(g)){closeLocked(s);s.latest=null;s.epoch++;s.status=message}
                    else try{g.close()}catch(_:Exception){}
                }
                override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) {
                    synchronized(lock) {
                        if(!current(g)){try{g.close()}catch(_:Exception){};return}
                        if(status!=BluetoothGatt.GATT_SUCCESS || newState==BluetoothProfile.STATE_DISCONNECTED) {
                            fail(g,"Desconectado BLE (código $status)");return
                        }
                        if(newState==BluetoothProfile.STATE_CONNECTED) {
                            try {
                                s.status="Buscando servicio BMI160…"
                                if(!g.discoverServices())fail(g,"No se pudo iniciar búsqueda de servicios")
                            }catch(e:Exception){fail(g,"Error BLE: ${e.message}")}
                        }
                    }
                }
                override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
                    synchronized(lock) {
                        if(!current(g))return
                        if(status!=BluetoothGatt.GATT_SUCCESS){fail(g,"Error al descubrir servicios: $status");return}
                        try {
                            val c=g.getService(serviceUuid)?.getCharacteristic(dataUuid)
                            val d=c?.getDescriptor(cccdUuid)
                            if(c==null || d==null || (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0) {
                                fail(g,"Este dispositivo no tiene el firmware BMI160 BLE BIN20 v1");return
                            }
                            if(!g.setCharacteristicNotification(c,true)){fail(g,"No se pudieron habilitar notificaciones");return}
                            d.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            s.status="Activando datos BMI160…"
                            if(!g.writeDescriptor(d))fail(g,"No se pudo escribir la suscripción BLE")
                        }catch(e:Exception){fail(g,"Error de suscripción: ${e.message}")}
                    }
                }
                override fun onDescriptorWrite(g:BluetoothGatt,descriptor:BluetoothGattDescriptor,status:Int) {
                    synchronized(lock) {
                        if(!current(g) || descriptor.uuid!=cccdUuid)return
                        if(status!=BluetoothGatt.GATT_SUCCESS){fail(g,"Suscripción rechazada: $status");return}
                        s.connecting=false;s.connected=true;s.status="Conectado BLE; esperando BMI160"
                        s.waiter?.countDown();s.waiter=null
                    }
                }
                // API anterior a 33; Android mantiene este callback por compatibilidad.
                override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {
                    if(c.uuid!=dataUuid)return
                    val bytes=c.value?.clone() ?: return
                    synchronized(lock) {
                        if(!current(g) || !s.connected)return
                        val sample=Bmi160Sample.parseBle(bytes,SystemClock.elapsedRealtime())
                        if(sample==null){s.invalid++;return}
                        val old=s.latest
                        if(old!=null && sample.sequence==old.sequence && sample.deviceMs==old.deviceMs){s.invalid++;return}
                        if(old!=null) {
                            // Reinicios/wrap invalidan la calibracion; no contar millones de saltos.
                            if(sample.sequence<=old.sequence || sample.deviceMs<=old.deviceMs)s.epoch++
                            else s.missed+=(sample.sequence-old.sequence-1).coerceAtLeast(0)
                        }
                        s.latest=sample;s.received++;s.status="Recibiendo BMI160 por BLE"
                    }
                }
            }
            try {
                s.gatt=if(Build.VERSION.SDK_INT>=23)
                    device.device.connectGatt(context.applicationContext,false,callback,BluetoothDevice.TRANSPORT_LE)
                else device.device.connectGatt(context.applicationContext,false,callback)
                if(s.gatt==null)throw IOException("Android no pudo crear la conexión GATT")
            }catch(e:Exception){closeLocked(s);s.status="No se pudo conectar: ${e.message}";throw e}
        }
        try {
            val completed=done.await(15,TimeUnit.SECONDS)
            synchronized(lock) {
                if(s.generation!=token)throw IOException("Conexión cancelada")
                if(!completed && !s.connected){closeLocked(s);s.status="Tiempo de conexión BLE agotado"}
                if(!s.connected)throw IOException(s.status)
            }
        }catch(e:InterruptedException) {
            synchronized(lock){if(s.generation==token){closeLocked(s);s.status="Conexión interrumpida"}}
            Thread.currentThread().interrupt();throw IOException("Conexión interrumpida",e)
        }
    }
    /** El firmware de diagnostico anterior tampoco ejecutaba comandos de letras. */
    @Throws(IOException::class)
    fun sendCommand(command:Char,hand:SensorHand=SensorHand.RIGHT) {
        throw IOException("El firmware BLE BMI160 solo transmite sensores; no ejecuta comandos de letras ($command, ${hand.label})")
    }
    private fun closeLocked(s:Session) {
        val g=s.gatt;s.gatt=null;s.connected=false;s.connecting=false
        try{g?.disconnect()}catch(_:Exception){}
        try{g?.close()}catch(_:Exception){}
        s.name=null;s.address=null;s.waiter?.countDown();s.waiter=null
    }
    fun disconnect(hand:SensorHand){synchronized(lock){
        val s=sessions.getValue(hand);s.generation++;closeLocked(s);s.latest=null;s.epoch++;s.status="Desconectado"
    }}
    fun disconnect(){SensorHand.values().forEach{disconnect(it)}}
}
