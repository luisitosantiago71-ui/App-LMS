import android.bluetooth.*
import android.content.Context
import com.mechrobotix.aprendels.*
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FakeSocket(val fail:Boolean=false,val waitConnect:Boolean=false):BluetoothSocket(){
 private val incoming=PipedInputStream(8192)
 private val feed=PipedOutputStream(incoming)
 val sent=ByteArrayOutputStream()
 val gate=CountDownLatch(if(waitConnect)1 else 0)
 @Volatile var closed=false
 override val inputStream:InputStream get()=incoming
 override val outputStream:OutputStream get()=sent
 override fun connect(){gate.await(2,TimeUnit.SECONDS);if(fail||closed)throw IOException("test failure");isConnected=true}
 override fun close(){closed=true;isConnected=false;gate.countDown();runCatching{feed.close()};runCatching{incoming.close()}}
 fun emit(s:String){feed.write(s.toByteArray());feed.flush()}
}
fun device(address:String,socket:FakeSocket)=PairedBluetoothDevice("same-name",address,
 object:BluetoothDevice("same-name",address){override fun createRfcommSocketToServiceRecord(uuid:UUID)=socket})
fun waitFor(message:String,test:()->Boolean){val until=System.currentTimeMillis()+2500;while(!test()){check(System.currentTimeMillis()<until){message};Thread.sleep(5)}}
fun line(seq:Int,x:Double=0.0)="IMU1,$seq,${seq*20},$x,0,1,0,0,0\n"
fun main(){
 val c=Esp32BluetoothClient;val ctx=Context();val l=SensorHand.LEFT;val r=SensorHand.RIGHT
 val left=FakeSocket();val right=FakeSocket()
 c.connect(ctx,device("AA",left),l);c.connect(ctx,device("BB",right),r)
 check(c.state(l).connected && c.state(r).connected)
 left.emit(line(1,.1));right.emit(line(1,.9))
 waitFor("independent samples"){c.state(l).sample!=null&&c.state(r).sample!=null}
 check(c.state(l).sample!!.ax==.1 && c.state(r).sample!!.ax==.9)
 println("PASS simultaneous streams, identical names and sequences stay separate")
 left.emit(line(4,.2));right.emit("garbage\n")
 waitFor("counters"){c.state(l).missed==2L&&c.state(r).invalid==1L}
 check(c.state(r).missed==0L&&c.state(l).invalid==0L)
 println("PASS isolated packet counters")
 val duplicate=FakeSocket();check(runCatching{c.connect(ctx,device("AA",duplicate),r)}.isFailure)
 check(c.state(l).connected&&c.state(r).connected&&duplicate.closed)
 println("PASS duplicate address rejected without disconnecting either hand")
 c.sendCommand('A',l);c.sendCommand('B',r)
 check(left.sent.toString()=="A\n"&&right.sent.toString()=="B\n")
 println("PASS commands go only to requested hand")
 val epoch=c.state(l).epoch;left.emit(line(1,.3))
 waitFor("epoch"){c.state(l).epoch>epoch}
 check(c.state(r).epoch==1L)
 right.emit("x".repeat(200)+"\n"+line(2,.8))
 waitFor("overflow recovery"){c.state(r).received==2L}
 check(c.state(r).invalid==2L&&c.state(r).sample!!.ax==.8)
 println("PASS reboot epoch and oversized packet recovery remain per hand")
 c.disconnect(l);check(!c.state(l).connected&&c.state(l).sample==null&&c.state(r).connected)
 right.emit(line(3));waitFor("other hand survives"){c.state(r).received==3L}
 println("PASS disconnecting one preserves other stream")
 check(runCatching{c.connect(ctx,device("CC",FakeSocket(fail=true)),l)}.isFailure)
 check(c.state(r).connected)
 println("PASS failed connection preserves other hand")
 val slow=FakeSocket(waitConnect=true)
 val task=thread {runCatching{c.connect(ctx,device("DD",slow),l)}}
 waitFor("pending"){c.state(l).connecting};c.disconnect(l)
 val newer=FakeSocket();c.connect(ctx,device("EE",newer),l);task.join()
 check(c.state(l).connected&&c.state(l).address=="EE"&&c.state(r).connected)
 println("PASS canceled pending session cannot close replacement")
 val stale=c.connectionToken(l);c.disconnect(l)
 val staleSocket=FakeSocket()
 check(runCatching{c.connectIfCurrent(ctx,device("FF",staleSocket),stale,l)}.isFailure)
 check(staleSocket.closed&&!c.state(l).connected&&c.state(r).connected)
 c.disconnect();check(!c.state(l).connected&&!c.state(r).connected)
 println("PASS queued stale token rejected; disconnect-all closes both")
}
