package com.mechrobotix.aprendels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

data class PairedBluetoothDevice(val name:String,val address:String,val device:BluetoothDevice)

/** Shared SPP connection: keeps the existing command API and adds bounded IMU reception. */
@SuppressLint("MissingPermission")
object Esp32BluetoothClient {
    private val sppUuid=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val lock=Any()
    @Volatile private var socket:BluetoothSocket?=null
    @Volatile private var pending:BluetoothSocket?=null
    private var output:OutputStream?=null
    @Volatile private var generation=0
    @Volatile var connectedName:String?=null; private set
    @Volatile var latestSample:Bmi160Sample?=null; private set
    @Volatile var receivedPackets=0L; private set
    @Volatile var missedPackets=0L; private set
    @Volatile var invalidPackets=0L; private set
    @Volatile var streamEpoch=0L; private set
    @Volatile var connectionStatus="Desconectado"; private set
    val isConnected:Boolean get()=socket?.isConnected==true
    fun hasBluetoothConnectPermission(context:Context)=Build.VERSION.SDK_INT<31 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    fun getBluetoothAdapter(context:Context):BluetoothAdapter?=context.getSystemService(BluetoothManager::class.java)?.adapter
    fun isBluetoothEnabled(context:Context)=hasBluetoothConnectPermission(context) && getBluetoothAdapter(context)?.isEnabled==true
    fun listPairedDevices(context:Context):List<PairedBluetoothDevice> {
        if(!hasBluetoothConnectPermission(context))return emptyList()
        return getBluetoothAdapter(context)?.bondedDevices.orEmpty().map {
            PairedBluetoothDevice(it.name ?: "Sin nombre",it.address,it)
        }.sortedWith(compareByDescending<PairedBluetoothDevice>{it.name.startsWith("ESP32_Mano")}.thenBy{it.name})
    }
    // Call before scheduling connect; disconnect invalidates queued and in-flight attempts.
    fun connectionToken():Int=generation
    fun connect(context:Context,device:PairedBluetoothDevice)=connectIfCurrent(context,device,connectionToken())
    fun connectIfCurrent(context:Context,device:PairedBluetoothDevice,expected:Int) {
        if(!hasBluetoothConnectPermission(context))throw SecurityException("Falta permiso Bluetooth")
        val candidate=device.device.createRfcommSocketToServiceRecord(sppUuid)
        val token:Int
        synchronized(lock) {
            if(expected!=generation){candidate.close();throw IOException("Conexión cancelada")}
            closeLocked();generation++;token=generation;pending=candidate
            latestSample=null;receivedPackets=0;missedPackets=0;invalidPackets=0;streamEpoch++
            connectionStatus="Conectando…"
        }
        // Closing a socket cancels a blocked connect. Timeout cannot close a newer session.
        Thread {
            try {Thread.sleep(12000)} catch(_:InterruptedException){return@Thread}
            synchronized(lock) {if(generation==token && pending===candidate) {
                try {candidate.close()}catch(_:Exception){}
            }}
        }.apply {isDaemon=true;start()}
        try {
            candidate.connect()
            synchronized(lock) {
                if(token!=generation)throw IOException("Conexión cancelada")
                pending=null;socket=candidate;output=candidate.outputStream;connectedName=device.name
                connectionStatus="Conectado a ${device.name}; esperando IMU1"
            }
            Thread {readLoop(candidate,token)}.apply {name="BMI160-reader";isDaemon=true;start()}
        } catch(e:Exception) {
            try {candidate.close()}catch(_:Exception){}
            synchronized(lock) {if(token==generation) {closeLocked();latestSample=null;connectionStatus="No se pudo conectar: ${e.message}"}}
            throw e
        }
    }
    private fun readLoop(s:BluetoothSocket,token:Int) {
        try {
            val input=s.inputStream;val buffer=ByteArray(512);val line=StringBuilder();var overflow=false
            while(token==generation) {
                val n=input.read(buffer);if(n<0)throw IOException("El ESP32 cerró la conexión")
                for(i in 0 until n) {
                    val c=(buffer[i].toInt() and 255).toChar()
                    if(c=='\n') {
                        val sample=if(!overflow) Bmi160Sample.parse(line.toString(),SystemClock.elapsedRealtime()) else null
                        synchronized(lock) {
                            if(token!=generation)return
                            if(sample==null)invalidPackets++ else {
                                val previous=latestSample
                                if(previous!=null && sample.sequence==previous.sequence && sample.deviceMs==previous.deviceMs) {
                                    invalidPackets++
                                } else {
                                    if(previous!=null) {
                                        if(sample.sequence<=previous.sequence || sample.deviceMs<=previous.deviceMs)streamEpoch++
                                        else missedPackets+=(sample.sequence-previous.sequence-1).coerceAtLeast(0)
                                    }
                                    latestSample=sample;receivedPackets++;connectionStatus="Recibiendo BMI160"
                                }
                            }
                        }
                        line.setLength(0);overflow=false
                    } else if(c!='\r' && !overflow) {
                        if(line.length>=180){line.setLength(0);overflow=true}else line.append(c)
                    }
                }
            }
        } catch(e:Exception) {
            synchronized(lock) {if(token==generation){closeLocked();latestSample=null;connectionStatus="Desconectado: ${e.message}"}}
        }
    }
    @Throws(IOException::class)
    fun sendCommand(command:Char) {
        val c=command.uppercaseChar()
        if(c !in 'A'..'Z' && c!='0')throw IOException("Comando no válido")
        synchronized(lock) {
            val out=output ?: throw IOException("No hay conexión activa")
            try {out.write(byteArrayOf(c.code.toByte(),10));out.flush()}
            catch(e:IOException){disconnect();throw e}
        }
    }
    private fun closeLocked() {
        try{pending?.close()}catch(_:Exception){}
        try{socket?.close()}catch(_:Exception){}
        pending=null;socket=null;output=null;connectedName=null
    }
    fun disconnect() {synchronized(lock){generation++;closeLocked();latestSample=null;connectionStatus="Desconectado"}}
}
