package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/** Dos datos independientes: puntos para evaluar y contorno para dibujar. */
data class ReferenceTemplate(
    val width: Int, val height: Int,
    val landmarks: List<NormalizedLandmark>, val outline: List<PointF>
) {
    companion object {
        fun load(context: Context, letter: String, width: Int, height: Int): ReferenceTemplate? =
            runCatching {
                val raw = context.getSharedPreferences("reference_templates_v2", Context.MODE_PRIVATE)
                    .getString(letter, null) ?: return@runCatching null
                val j = JSONObject(raw)
                require(j.getInt("width") == width && j.getInt("height") == height)
                val p = j.getJSONArray("points"); val o = j.getJSONArray("outline")
                require(p.length() == 21 && o.length() >= 3)
                val landmarks = (0 until p.length()).map { i ->
                    val a = p.getJSONArray(i)
                    val x = a.getDouble(0).toFloat(); val y = a.getDouble(1).toFloat()
                    val z = a.getDouble(2).toFloat()
                    require(x.isFinite() && y.isFinite() && z.isFinite() && x in 0f..1f && y in 0f..1f)
                    NormalizedLandmark.create(x,y,z)
                }
                val outline = (0 until o.length()).map { i ->
                    val a = o.getJSONArray(i)
                    val x = a.getDouble(0).toFloat(); val y = a.getDouble(1).toFloat()
                    require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                    PointF(x,y)
                }
                ReferenceTemplate(width,height,landmarks,outline)
            }.getOrNull()

        fun automatic(bitmap: Bitmap, points: List<NormalizedLandmark>): ReferenceTemplate {
            require(points.size == 21)
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w*h)
            bitmap.getPixels(pixels,0,w,0,0,w,h)
            val mask = BooleanArray(w*h)
            val wx = points[0].x()*w; val wy = points[0].y()*h
            val dx = points[9].x()*w-wx; val dy = points[9].y()*h-wy
            val length = hypot(dx,dy).coerceAtLeast(1f)
            for (i in pixels.indices) {
                val c = pixels[i]; val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val background = minOf(r,g,b)>165 && maxOf(r,g,b)-minOf(r,g,b)<28
                // Corta el antebrazo detrás de la muñeca, siguiendo el eje de la palma.
                val alongPalm = ((i%w-wx)*dx+(i/w-wy)*dy)/length
                mask[i] = Color.alpha(c)>32 && !background && alongPalm >= -.18f*length
            }
            // Conserva únicamente el componente que contiene (o está más cerca de) la palma.
            val centerX = listOf(0,5,9,13,17).map { points[it].x()*w }.average()
            val centerY = listOf(0,5,9,13,17).map { points[it].y()*h }.average()
            val seed = mask.indices.filter { mask[it] }.minByOrNull {
                val x=it%w-centerX; val y=it/w-centerY; x*x+y*y
            } ?: error("No se pudo separar la mano del fondo")
            val keep = BooleanArray(w*h); val queue = IntArray(w*h)
            var head=0; var tail=0; queue[tail++]=seed; keep[seed]=true
            fun add(i: Int) { if (mask[i] && !keep[i]) { keep[i]=true; queue[tail++]=i } }
            while (head<tail) {
                val i=queue[head++]; val x=i%w; val y=i/w
                if(x>0) add(i-1); if(x<w-1) add(i+1)
                if(y>0) add(i-w); if(y<h-1) add(i+w)
            }
            // Aristas orientadas de píxeles. Se elige el ciclo exterior de mayor área.
            val edges = HashMap<Int, MutableList<Int>>()
            fun vertex(x:Int,y:Int)=y*(w+1)+x
            fun edge(ax:Int,ay:Int,bx:Int,by:Int) {
                edges.getOrPut(vertex(ax,ay)){ mutableListOf() }.add(vertex(bx,by))
            }
            for(y in 0 until h) for(x in 0 until w) if(keep[y*w+x]) {
                if(y==0 || !keep[(y-1)*w+x]) edge(x,y,x+1,y)
                if(x==w-1 || !keep[y*w+x+1]) edge(x+1,y,x+1,y+1)
                if(y==h-1 || !keep[(y+1)*w+x]) edge(x+1,y+1,x,y+1)
                if(x==0 || !keep[y*w+x-1]) edge(x,y+1,x,y)
            }
            var best = emptyList<Int>(); var bestArea=0.0
            while(edges.isNotEmpty()) {
                val start=edges.keys.first(); var current=start
                val loop=mutableListOf<Int>()
                do {
                    loop.add(current)
                    val outgoing=edges[current] ?: break
                    val next=outgoing.removeAt(outgoing.lastIndex)
                    if(outgoing.isEmpty()) edges.remove(current)
                    current=next
                } while(current!=start)
                if(current!=start || loop.size<3) continue
                var area=0.0
                for(i in loop.indices) {
                    val a=loop[i]; val b=loop[(i+1)%loop.size]
                    area += (a%(w+1)).toDouble()*(b/(w+1))-(b%(w+1)).toDouble()*(a/(w+1))
                }
                if(abs(area)>bestArea) { bestArea=abs(area); best=loop }
            }
            require(best.size>=3) { "Contorno no disponible" }
            // Reduce vértices conservando orden. Solo un ciclo: sin mancha ni agujeros internos.
            val step=maxOf(1,best.size/180)
            val outline=best.filterIndexed { i,_ -> i%step==0 }.map {
                PointF((it%(w+1)).toFloat()/w,(it/(w+1)).toFloat()/h)
            }
            return ReferenceTemplate(w,h,points.toList(),outline)
        }
    }
}
