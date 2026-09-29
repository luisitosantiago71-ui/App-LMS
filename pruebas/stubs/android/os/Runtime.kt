package android.os
object Build { object VERSION { const val SDK_INT=36 } }
object SystemClock { fun elapsedRealtime()=System.nanoTime()/1000000 }
