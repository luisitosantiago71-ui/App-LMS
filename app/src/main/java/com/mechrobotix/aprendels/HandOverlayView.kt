package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Dibujado y comparación espacial 2D; no es un clasificador entrenado de LSM. */
class HandOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    data class Match(val progress: Int, val close: Boolean, val correct: Boolean)
    private var hands: List<List<NormalizedLandmark>> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var target: List<NormalizedLandmark> = emptyList()
    private var reference: ReferenceTemplate? = null
    private var guideWidthPercent = 28

    // POSICIÓN DEL CENTRO DE LA GUÍA EN EL ÁREA VISIBLE DEL OVERLAY.
    // Cambia únicamente estos dos valores para hacer pruebas:
    // X: menor = izquierda; mayor = derecha. 0.50f = centro horizontal.
    // Y: menor = arriba; mayor = abajo. 0.50f = centro vertical.
    // Valores iniciales aproximados al área de tu mano en la captura.
    private var guideCenterX = 0.28f
    private var guideCenterY = 0.38f

    // ROTACIÓN INICIAL: cambia 0f por el ángulo que quieras probar.
    // Positivo = horario; negativo = antihorario; 0f = sin giro.
    // Rotación 2D alrededor del centro del contorno (no giro de muñeca en 3D).
    private var guideRotationDegrees = 0f
    private var guideMirrored = false
    private val contour = Path()
    private val transformedContour = Path()
    private val contourTransform = Matrix()
    private var validated = false
    private var tint = Color.RED
    private val cameraRect = RectF()
    private val ghostRect = RectF()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val connections = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17
    )

    // Ejecutar todos los métodos públicos desde el hilo de interfaz.
    fun clearReference() {
        reference = null;
        contour.reset();
        target = emptyList();
        validated = false;
        tint = Color.RED
        invalidate()
    }

    fun setReference(template: ReferenceTemplate) {
        reference = template; target = template.landmarks
        contour.reset()
        template.outline.forEachIndexed { i,p ->
            val x=p.x*template.width; val y=p.y*template.height
            if(i==0) contour.moveTo(x,y) else contour.lineTo(x,y)
        }
        contour.close()
        validated = false; tint = Color.RED; invalidate()
    }

    /** Porcentaje visible del ancho de pantalla, compensando el zoom 1.30 del overlay. */
    fun setGuideWidthPercent(percent: Int) {
        guideWidthPercent = percent.coerceIn(15,60)
        resetAttempt()
    }

    /**
     * Gira juntos el contorno y los puntos objetivo. Llamar desde el hilo de interfaz.
     * Ejemplo en la Activity: binding.overlayCanvasView.setGuideRotation(-15f)
     * Si se cambia DURANTE una práctica, llamar también a resetValidation() en
     * PracticeSignActivity para reiniciar su temporizador, mensaje y botón Siguiente.
     */
    fun setGuideRotation(degrees: Float) {
        require(degrees.isFinite()) { "El ángulo debe ser finito" }
        guideRotationDegrees = degrees % 360f
        resetAttempt()
    }

    fun getGuideRotation(): Float = guideRotationDegrees

    /** Actualización conjunta desde los controles. Valores X/Y normalizados entre 0 y 1.
     * La Activity reinicia su temporizador y guarda los ajustes por letra.
     */
    fun setGuideTransform(widthPercent: Int, centerX: Float, centerY: Float, rotationDegrees: Float, mirrored: Boolean = false) {
        require(centerX.isFinite() && centerY.isFinite() && rotationDegrees.isFinite())
        guideWidthPercent = widthPercent.coerceIn(15,60)
        guideCenterX = centerX.coerceIn(0f,1f)
        guideCenterY = centerY.coerceIn(0f,1f)
        guideRotationDegrees = rotationDegrees % 360f
        guideMirrored = mirrored
        resetAttempt()
    }

    fun setValidated() { validated = true; tint = Color.GREEN; invalidate() }

    fun resetAttempt() {
        validated = false; tint = Color.RED; hands = emptyList(); invalidate()
    }

    fun setResults(result: HandLandmarkerResult, sourceWidth: Int, sourceHeight: Int) {
        hands = result.landmarks()
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        invalidate()
    }

    private fun updateRects() {
        if (width <= 0 || height <= 0) return
        if (sourceWidth > 0 && sourceHeight > 0) {
            val s = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
            val w = sourceWidth * s; val h = sourceHeight * s
            cameraRect.set((width-w)/2f, (height-h)/2f, (width+w)/2f, (height+h)/2f)
        }
        reference?.let { ref ->
            val minX=ref.outline.minOf { it.x }*ref.width
            val maxX=ref.outline.maxOf { it.x }*ref.width
            val minY=ref.outline.minOf { it.y }*ref.height
            val maxY=ref.outline.maxOf { it.y }*ref.height
            var s=minOf(
                width*(guideWidthPercent/100f)/scaleX.coerceAtLeast(.1f)/(maxX-minX).coerceAtLeast(1f),
                height*.40f/scaleY.coerceAtLeast(.1f)/(maxY-minY).coerceAtLeast(1f)
            )
            // Convertir la posición visible a coordenadas locales del Canvas.
            // Compensa el zoom 1.30 configurado por PracticeSignActivity.
            // Se limita el centro para que el contorno no salga de los bordes.
            val zoomX = scaleX.coerceAtLeast(.1f)
            val zoomY = scaleY.coerceAtLeast(.1f)
            // Caja envolvente tras girar: evita recortes también a 90° o 180°.
            val radians = Math.toRadians(guideRotationDegrees.toDouble())
            val c = abs(cos(radians)).toFloat()
            val sn = abs(sin(radians)).toFloat()
            val rotatedWidth = (maxX-minX)*c + (maxY-minY)*sn
            val rotatedHeight = (maxX-minX)*sn + (maxY-minY)*c
            s = minOf(s,
                width*.98f/(rotatedWidth*zoomX).coerceAtLeast(1f),
                height*.98f/(rotatedHeight*zoomY).coerceAtLeast(1f))
            val halfVisibleWidth = rotatedWidth*s*zoomX*.5f
            val halfVisibleHeight = rotatedHeight*s*zoomY*.5f
            val visibleCenterX = (width*guideCenterX).coerceIn(
                halfVisibleWidth, width-halfVisibleWidth)
            val visibleCenterY = (height*guideCenterY).coerceIn(
                halfVisibleHeight, height-halfVisibleHeight)
            val centerX = pivotX + (visibleCenterX-pivotX)/zoomX
            val centerY = pivotY + (visibleCenterY-pivotY)/zoomY
            val left=centerX-(minX+maxX)*.5f*s
            val top=centerY-(minY+maxY)*.5f*s
            // Foto completa y landmarks comparten la MISMA transformación del contorno.
            ghostRect.set(left,top,left+ref.width*s,top+ref.height*s)
            // Una sola matriz para el dibujo Y para la evaluación de los 21 puntos.
            contourTransform.setScale(s,s)
            contourTransform.postTranslate(left,top)
            contourTransform.postRotate(guideRotationDegrees,centerX,centerY)
            // Espejo horizontal de la guía ya girada, alrededor de su centro.
            // La misma matriz transforma los 21 objetivos al evaluar la otra mano.
            if (guideMirrored) contourTransform.postScale(-1f,1f,centerX,centerY)
        }
    }

    private fun point(p: NormalizedLandmark, rect: RectF, mirror: Boolean): PointF =
        PointF(rect.left + (if (mirror) 1f-p.x() else p.x())*rect.width(),
            rect.top + p.y()*rect.height())

    fun evaluate(): Match {
        updateRects()
        if (validated) return Match(100, true, true)
        if (target.size != 21 || hands.size != 1 || hands[0].size != 21 ||
            cameraRect.isEmpty || ghostRect.isEmpty) {
            tint = Color.RED; invalidate(); return Match(0, false, false)
        }
        val ref = reference ?: return Match(0, false, false)
        val expected = target.map {
            val xy = floatArrayOf(it.x()*ref.width, it.y()*ref.height)
            contourTransform.mapPoints(xy)
            PointF(xy[0],xy[1])
        }
        val actual = hands[0].map { point(it, cameraRect, true) }
        val palm = hypot(expected[0].x-expected[9].x, expected[0].y-expected[9].y)
        if (palm < 1f) return Match(0, false, false)
        val errors = actual.indices.map { i ->
            hypot(actual[i].x-expected[i].x, actual[i].y-expected[i].y) / palm
        }
        val mean = errors.average().toFloat()
        val worst = errors.maxOrNull() ?: Float.MAX_VALUE
        // Distancias relativas a la palma, NO grados ni probabilidad de acierto.
        val correct = mean <= .15f && worst <= .32f
        val close = mean <= .45f && worst <= .80f
        val progress = ((1f - mean/.80f).coerceIn(0f, 1f)*100).toInt().coerceAtMost(99)
        tint = when { correct -> Color.GREEN; close -> Color.YELLOW; else -> Color.RED }
        invalidate()
        return Match(progress, close, correct)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateRects()
        // La guía se dibuja incluso cuando no hay una mano detectada.
        reference?.let {
            ghostPaint.color=tint
            ghostPaint.alpha=230
            ghostPaint.strokeWidth=2.5f*resources.displayMetrics.density/scaleX.coerceAtLeast(.1f)
            contour.transform(contourTransform,transformedContour)
            canvas.drawPath(transformedContour,ghostPaint)
        }
        if (cameraRect.isEmpty) return
        for (hand in hands) {
            if (hand.size != 21) continue
            val points = hand.map { point(it, cameraRect, true) }
            for ((a,b) in connections) {
                canvas.drawLine(points[a].x, points[a].y, points[b].x, points[b].y, line)
            }
            for (p in points) canvas.drawCircle(p.x, p.y, 7f, dot)
        }
    }

}
