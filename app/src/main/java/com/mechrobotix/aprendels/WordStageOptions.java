package com.mechrobotix.aprendels;

/** Opciones del modo de práctica por etapas. Se sobrescriben en validation del catálogo. */
public final class WordStageOptions {
    public int stages=3;                 // Inicio, movimiento, final; 4/5/6 agregan cambios intermedios.
    public int maxFingerMismatches=1;     // Dedos claramente abiertos/cerrados distintos (sin pulgar).
    public int motionSamples=2;          // Cambios observados separados; un salto de un cuadro no basta.
    public double minDisplacement=.25;   // Palmas; mínimo de desplazamiento/cambio observable.
    public double motionNoise=.07;       // Ignorar pequeños cambios del detector.
    public long poseHoldMs=100;          // Tiempo válido de cada postura intermedia.
    public long matchGraceMs=250;        // Un fallo breve no borra el tiempo válido acumulado.
    public boolean sharedRouteOrigin=false; // Origen común para manos que entran durante la seña.
    public double pathTolerance=0;      // 0 desactiva; >0 exige recorrido ordenado (error en palmas).
    public double finalPoseScale=1;     // Multiplica las tolerancias de postura final; menor = más preciso.
    public void validate() {
        if(!Double.isFinite(pathTolerance) || pathTolerance<0 || pathTolerance>2 ||
           !Double.isFinite(finalPoseScale) || finalPoseScale<.25 || finalPoseScale>1 ||
           maxFingerMismatches<0 || maxFingerMismatches>4 || stages<3 || stages>6 || motionSamples<2 || motionSamples>6 ||
           !Double.isFinite(minDisplacement) || minDisplacement<.10 || minDisplacement>2 ||
           !Double.isFinite(motionNoise) || motionNoise<.03 || motionNoise>=minDisplacement ||
           poseHoldMs<60 || poseHoldMs>1000 || matchGraceMs<0 || matchGraceMs>600)
            throw new IllegalArgumentException("validation fuera de rango: stages 3–6, motionSamples 2–6, pathTolerance 0–2, finalPoseScale 0.25–1; revisa los demás márgenes");
    }
}
