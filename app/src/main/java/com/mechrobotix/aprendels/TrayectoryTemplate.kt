package com.mechrobotix.aprendels

import android.graphics.PointF

data class TrajectoryTemplate(
    val letter: String,
    val trackedNode: Int, // El nodo (punto de la mano) que vamos a rastrear
    val waypoints: List<PointF>,
    val totalDurationMs: Long = 2000L,
    val videoResId: Int? = null // <--- DEBE ESTAR AQUÍ
)

object TrajectoryCatalog {
    val trajectories = mapOf(
        "J" to TrajectoryTemplate(
            letter = "J", trackedNode = 20, // Punta del meñique
            waypoints = listOf(PointF(0.6f, 0.3f), PointF(0.6f, 0.7f), PointF(0.4f, 0.7f)),
            videoResId = R.raw.letra_j
        ),
        "Z" to TrajectoryTemplate(
            letter = "Z", trackedNode = 8, // Punta del índice
            waypoints = listOf(PointF(0.4f, 0.3f), PointF(0.7f, 0.3f), PointF(0.4f, 0.7f), PointF(0.7f, 0.7f))
        ),
        "Ñ" to TrajectoryTemplate(
            letter = "Ñ", trackedNode = 0, // Usamos la muñeca o el centro para el vaivén
            waypoints = listOf(PointF(0.4f, 0.5f), PointF(0.7f, 0.5f), PointF(0.4f, 0.5f)) // Derecha y regreso
        ),
        "X" to TrajectoryTemplate(
            letter = "X", trackedNode = 8, // Índice encorvado
            waypoints = listOf(PointF(0.7f, 0.4f), PointF(0.3f, 0.4f)) // Retracción hacia el cuerpo (simulado izq-der)
        ),
        "Q" to TrajectoryTemplate(
            letter = "Q", trackedNode = 0, // Muñeca
            waypoints = listOf(PointF(0.5f, 0.4f), PointF(0.5f, 0.7f)) // Movimiento hacia abajo
        ),
        "K" to TrajectoryTemplate(
            letter = "K", trackedNode = 0, // Muñeca
            waypoints = listOf(PointF(0.5f, 0.6f), PointF(0.5f, 0.4f)) // Movimiento hacia arriba
        )
    )
}