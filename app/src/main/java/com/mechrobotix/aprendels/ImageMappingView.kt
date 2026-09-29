package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

/** Editor local: arrastrar articulaciones o volver a trazar el contorno en un solo gesto. */
class ImageMappingView(context: Context, private val photo: Bitmap,
                          private val original: ReferenceTemplate?) : View(context) {
    var tracing = false
        set(value) { field=value; selected=-1; invalidate() }
    var onChanged: (() -> Unit)? = null
    var onHint: ((String)->Unit)? = null
    private val points=original?.landmarks?.map { PointF(it.x(),it.y()) }?.toMutableList() ?: mutableListOf<PointF>()
    private var outline=original?.outline?.map { PointF(it.x,it.y) } ?: emptyList()
    private val draft=mutableListOf<PointF>()
    private val imageRect=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected=-1
    private var addedThisGesture=false
    private val connections=arrayOf(0 to 1,1 to 2,2 to 3,3 to 4,0 to 5,5 to 6,6 to 7,7 to 8,
        0 to 9,9 to 10,10 to 11,11 to 12,0 to 13,13 to 14,14 to 15,15 to 16,
        0 to 17,17 to 18,18 to 19,19 to 20,5 to 9,9 to 13,13 to 17)

    fun instructions(): String {
        if(tracing) return "Rodea la mano en un solo trazo y vuelve al inicio."
        if(points.size==21) return "21 puntos colocados. Arrástralos para corregirlos y traza el contorno."
        val names=listOf("muñeca", "base del pulgar", "articulación MCP del pulgar",
            "articulación IP del pulgar", "punta del pulgar",
            "base del índice", "articulación PIP del índice", "articulación DIP del índice", "punta del índice",
            "base del medio", "articulación PIP del medio", "articulación DIP del medio", "punta del medio",
            "base del anular", "articulación PIP del anular", "articulación DIP del anular", "punta del anular",
            "base del meñique", "articulación PIP del meñique", "articulación DIP del meñique", "punta del meñique")
        return "Toca para colocar el punto ${points.size}: ${names[points.size]}."
    }

    fun snapshot(): ReferenceTemplate {
        require(points.size==21) { "Faltan ${21-points.size} puntos. ${instructions()}" }
        require(outline.size>=3) { "Activa Trazar contorno a mano y rodea la mano antes de guardar." }
        return ReferenceTemplate(photo.width,photo.height,
            points.mapIndexed { i,p -> NormalizedLandmark.create(p.x,p.y,original?.landmarks?.get(i)?.z() ?: 0f) },
            outline.map { PointF(it.x,it.y) })
    }

    fun removeLastPoint() {
        if(points.isNotEmpty()) points.removeAt(points.lastIndex)
        selected=-1; onChanged?.invoke(); onHint?.invoke(instructions()); invalidate()
    }

    fun resetDraft() {
        points.clear()
        original?.landmarks?.forEach { points.add(PointF(it.x(),it.y())) }
        outline=original?.outline?.map { PointF(it.x,it.y) } ?: emptyList()
        draft.clear(); selected=-1; onChanged?.invoke(); onHint?.invoke(instructions()); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s=minOf(width.toFloat()/photo.width,height.toFloat()/photo.height)
        val w=photo.width*s; val h=photo.height*s
        imageRect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        canvas.drawColor(Color.LTGRAY)
        paint.reset(); paint.isAntiAlias=true; paint.isFilterBitmap=true
        canvas.drawBitmap(photo,null,imageRect,paint)
        fun x(p:PointF)=imageRect.left+p.x*imageRect.width()
        fun y(p:PointF)=imageRect.top+p.y*imageRect.height()
        val path=Path(); val vertices=if(draft.size>1) draft else outline
        vertices.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p),y(p)) else path.lineTo(x(p),y(p)) }
        if(draft.isEmpty()) path.close()
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f*resources.displayMetrics.density
        paint.color=Color.rgb(0,90,230); canvas.drawPath(path,paint)
        if(!tracing) {
            paint.strokeWidth=resources.displayMetrics.density; paint.color=Color.rgb(0,110,0)
            for((a,b) in connections) if(a<points.size && b<points.size)
                canvas.drawLine(x(points[a]),y(points[a]),x(points[b]),y(points[b]),paint)
            for((i,p) in points.withIndex()) {
                paint.style=Paint.Style.FILL; paint.color=if(i==selected) Color.RED else Color.YELLOW
                canvas.drawCircle(x(p),y(p),5f*resources.displayMetrics.density,paint)
                paint.color=Color.BLACK; paint.textSize=12f*resources.displayMetrics.scaledDensity
                canvas.drawText(i.toString(),x(p)+6,y(p)-6,paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled || imageRect.isEmpty) return false
        val p=PointF(((event.x-imageRect.left)/imageRect.width()).coerceIn(0f,1f),
            ((event.y-imageRect.top)/imageRect.height()).coerceIn(0f,1f))
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if(!imageRect.contains(event.x,event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                addedThisGesture=false
                if(tracing) { draft.clear(); draft.add(p) }
                else if(points.size<21) {
                    points.add(p); selected=points.lastIndex; addedThisGesture=true
                    onHint?.invoke("Colocando punto $selected. Puedes arrastrarlo antes de soltar.")
                } else {
                    selected=points.indices.minByOrNull { i ->
                        hypot((points[i].x-p.x)*imageRect.width(),(points[i].y-p.y)*imageRect.height())
                    } ?: -1
                    if(selected>=0) {
                        val q=points[selected]
                        if(hypot((q.x-p.x)*imageRect.width(),(q.y-p.y)*imageRect.height())>28f*resources.displayMetrics.density) selected=-1
                    }
                    if(selected>=0) onHint?.invoke("Moviendo punto $selected")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if(tracing || selected>=0) onChanged?.invoke()
                if(tracing) {
                    val q=draft.lastOrNull()
                    if(q==null || hypot((q.x-p.x)*imageRect.width(),(q.y-p.y)*imageRect.height())>3f) draft.add(p)
                } else if(selected>=0) points[selected]=p
            }
            MotionEvent.ACTION_UP -> {
                if(tracing) {
                    if(draft.size>=12 && hypot((draft.first().x-draft.last().x)*imageRect.width(),(draft.first().y-draft.last().y)*imageRect.height())<40f*resources.displayMetrics.density) {
                        outline=draft.filterIndexed { i,_ -> i%maxOf(1,draft.size/500)==0 }.map { PointF(it.x,it.y) }
                        onHint?.invoke("Contorno trazado. Revisa antes de guardar.")
                    } else onHint?.invoke("Trazo corto o abierto: vuelve al inicio del contorno. Se conservó el anterior.")
                    draft.clear()
                }
                onChanged?.invoke()
                if(!tracing) onHint?.invoke(instructions())
                addedThisGesture=false
                selected=-1; parent?.requestDisallowInterceptTouchEvent(false); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                onChanged?.invoke()
                if(addedThisGesture && points.isNotEmpty()) points.removeAt(points.lastIndex)
                addedThisGesture=false; draft.clear(); selected=-1
                onHint?.invoke(instructions()); parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
