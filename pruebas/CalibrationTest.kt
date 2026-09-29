import com.mechrobotix.aprendels.*
fun main(){
 val left=Bmi160Calibration();val right=Bmi160Calibration()
 left.start();right.start()
 for(i in 0..160){
  left.add(Bmi160Sample.parse("IMU1,$i,${i*20},0,0,1,0.4,0,0",i*20L)!!)
  right.add(Bmi160Sample.parse("IMU1,$i,${i*20},0,0,1,-0.6,0,0",i*20L)!!)
 }
 check(left.ready&&right.ready)
 check(kotlin.math.abs(left.bx-0.4)<1e-6&&kotlin.math.abs(right.bx+0.6)<1e-6)
 left.reset();check(!left.ready&&right.ready)
 println("PASS independent calibrations and one-sided reset")
}
