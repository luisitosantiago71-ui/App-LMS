package com.mechrobotix.aprendels

import android.content.Context
import android.graphics.*
import android.view.View

/** Vista exclusiva del ejercicio. Limpia de guías geométricas. */
class MotionPracticeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var points: List<PointF> = emptyList()
    private val trail = ArrayDeque<PointF>()
    private var center: PointF? = null
    private var feedback: MotionPracticeEngine.Feedback? = null

    var currentTrajectory: TrajectoryTemplate? = null
    var reverse = false // Mantenido por compatibilidad

    private val edges = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20
    )

    fun clear() { points = emptyList(); center = null; trail.clear(); invalidate() }

    fun update(w: Int, h: Int, p: List<PointF>, sample: MotionPracticeEngine.Sample?, f: MotionPracticeEngine.Feedback) {
        sourceWidth = w; sourceHeight = h; points = p; feedback = f
        center = sample?.let { PointF(it.x.toFloat(), it.y.toFloat()) }
        if (f.stage == MotionPracticeEngine.Stage.PREPARE || f.stage == MotionPracticeEngine.Stage.HOLD_START) trail.clear()
        if (sample != null && (f.stage == MotionPracticeEngine.Stage.MOVE || f.stage == MotionPracticeEngine.Stage.HOLD_END)) {
            trail.addLast(PointF(sample.x.toFloat(), sample.y.toFloat()))
            while (trail.size > 240) trail.removeFirst()
        }
        invalidate()
    }

    fun showFeedback(f: MotionPracticeEngine.Feedback) { feedback = f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val w = if (sourceWidth > 0) sourceWidth else 3
        val h = if (sourceHeight > 0) sourceHeight else 4
        val scale = minOf(width.toFloat() / w, height.toFloat() / h)
        val rect = RectF((width - w * scale) / 2, (height - h * scale) / 2, (width + w * scale) / 2, (height + h * scale) / 2)

        fun px(p: PointF) = rect.left + p.x * rect.width()
        fun py(p: PointF) = rect.top + p.y * rect.height()

        // --- 1. DIBUJO DEL RASTRO (TRAIL) AMARILLO ---
        // Ahora es súper estable porque el motor rastrea el centro de la palma
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f) // Un poco más grueso para que destaque
        paint.color = Color.YELLOW
        paint.pathEffect = null
        val path = Path()
        trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p), py(p)) else path.lineTo(px(p), py(p)) }
        canvas.drawPath(path, paint)

        // --- 2. DIBUJO DEL ESQUELETO DE LA MANO ---
        paint.strokeWidth = dp(2f)
        paint.color = Color.GREEN
        if (points.size == 21) {
            edges.forEach { (a, b) -> canvas.drawLine(px(points[a]), py(points[a]), px(points[b]), py(points[b]), paint) }
            paint.style = Paint.Style.FILL
            points.forEach { canvas.drawCircle(px(it), py(it), dp(3f), paint) }
        }

        // --- 3. LA MAGIA: CONEXIÓN VISUAL ANCLA-DEDO ---
        if (points.size == 21 && center != null) {
            // Obtenemos qué dedo es el importante (por defecto el meñique 20)
            val trackedNode = currentTrajectory?.trackedNode ?: 20

            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = dp(2.5f)
            paint.pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f) // Efecto punteado

            // Trazamos un "láser" desde el nudillo central (9) hasta el dedo activo
            canvas.drawLine(px(points[9]), py(points[9]), px(points[trackedNode]), py(points[trackedNode]), paint)

            paint.pathEffect = null // Limpiamos el efecto para futuros trazos
        }

        // --- 4. PUNTO MAGENTA DE SEGUIMIENTO ---
        center?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.MAGENTA
            canvas.drawCircle(px(it), py(it), dp(6f), paint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}