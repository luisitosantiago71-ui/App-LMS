package android.bluetooth
import java.io.*
import java.util.UUID
open class BluetoothSocket {
 @Volatile var isConnected=false
 open val inputStream:InputStream get()=throw IOException()
 open val outputStream:OutputStream get()=throw IOException()
 open fun connect(){isConnected=true}
 open fun close(){isConnected=false}
}
open class BluetoothDevice(val name:String?,val address:String){
 open fun createRfcommSocketToServiceRecord(uuid:UUID):BluetoothSocket=BluetoothSocket()
}
class BluetoothAdapter { var isEnabled=true;var bondedDevices=emptySet<BluetoothDevice>() }
class BluetoothManager { val adapter=BluetoothAdapter() }
