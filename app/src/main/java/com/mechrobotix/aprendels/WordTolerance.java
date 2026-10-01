package com.mechrobotix.aprendels;

/** Ajustes de PALABRAS; no modifica las tolerancias del abecedario.
 * Distancias: unidades de palma estimada (muñeca/nudillos y ancho), no píxeles.
 * Grados: diferencias de flexión, no orientación absoluta de la muñeca.
 */
public final class WordTolerance {
    public double shape = 0.78;       // Promedio de diferencias de los 20 puntos relativos.
    public double meanFlex = 50.0;    // Diferencia media de flexión en grados.
    public double maxFlex = 105.0;    // Límite individual para un dedo/articulación.
    public double travel = 1.25;      // Margen de desplazamiento relativo, en palmas.
    public double travelRelative = 0.25; // Margen adicional para recorridos grandes.
    public double minMotionRatio = 0.25; // Fracción mínima del cambio entre etapas.
    public double minMotion = 0.10;   // Evita aprobar por temblor/ruido o mano inmóvil.
    public double directionCosine = -0.05; // Dirección gruesa: no recorrido exactamente opuesto.
    public double minTravelProgress = 0.50; // Alcanzar al menos la mitad de cada desplazamiento claro.
    public double stability = 0.40;   // Movimiento permitido al sostener inicio/final.
    public long startHoldMs = 220;
    public long endHoldMs = 280;
    public long lostGraceMs = 1100;
    public long maxAttemptMs = 45000;
    public long minAttemptMs = 900;
    public void validate() {
        if(!Double.isFinite(shape) || shape<=0 || shape>2 || !Double.isFinite(meanFlex) || meanFlex<=0 || meanFlex>180 ||
           !Double.isFinite(maxFlex) || maxFlex<meanFlex || maxFlex>180 || !Double.isFinite(travel) || travel<=0 || travel>4 ||
           !Double.isFinite(travelRelative) || travelRelative<0 || travelRelative>1 || !Double.isFinite(minMotionRatio) ||
           minMotionRatio<=0 || minMotionRatio>1 || !Double.isFinite(minMotion) || minMotion<=0 ||
           !Double.isFinite(directionCosine) || directionCosine<-1 || directionCosine>1 ||
           !Double.isFinite(minTravelProgress) || minTravelProgress<0 || minTravelProgress>1 || !Double.isFinite(stability) || stability<=0 || startHoldMs<100 || endHoldMs<100 || lostGraceMs<100 ||
           lostGraceMs>3000 || minAttemptMs<300 || maxAttemptMs<=minAttemptMs)
            throw new IllegalArgumentException("Tolerancias de palabras fuera de rango");
    }
}
