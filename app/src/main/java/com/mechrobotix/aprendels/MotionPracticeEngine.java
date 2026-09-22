package com.mechrobotix.aprendels;

/** Evaluador de recorrido usando Trayectoria Libre y Postura Inteligente. */
public final class MotionPracticeEngine {
    public static final double ZONE_RADIUS = 0.18; //antes 0.15
    public static final long MAX_GAP_MS = 1200; //antes 800

    public enum Stage { PREPARE, HOLD_START, MOVE, HOLD_END, SUCCESS }

    public static final class Sample {
        public final double x, y, palm, orientation, thumbSpread;
        public final String side;
        public final double[] flex;
        public Sample(double x, double y, double palm, double orientation,
                      String side, double[] flex, double thumbSpread) {
            this.x = x; this.y = y; this.palm = palm; this.orientation = orientation;
            this.side = side; this.flex = flex.clone(); this.thumbSpread = thumbSpread;
        }
    }

    public static final class Feedback {
        public final Stage stage;
        public final int progress;
        public final String message;
        public final int checkpoints;
        Feedback(Stage s, int p, String m, int c) {
            stage = s; progress = p; message = m; checkpoints = c;
        }
    }

    private boolean reverse;
    private Stage stage = Stage.PREPARE;
    private long last = -1, hold = -1, moveStart = -1;
    private Sample previous, initial;

    private double[][] waypoints = {{0.25, 0.58}, {0.75, 0.58}};
    private int currentTargetIndex = 1;

    private double offsetX = 0.0;
    private double offsetY = 0.0;

    private Feedback feedback = new Feedback(stage, 0, "Levanta la mano formando la letra", 0);

    public void setWaypoints(double[][] wp) {
        if (wp != null && wp.length >= 2) {
            this.waypoints = wp;
        }
        reset();
    }

    public void setReverse(boolean value) { reverse = value; reset(); }
    public boolean isReverse() { return reverse; }
    public Feedback getFeedback() { return feedback; }
    public Feedback reset() { return fail("Levanta la mano formando la letra para comenzar"); }

    private Feedback emit(int p, String text) {
        feedback = new Feedback(stage, p, text, currentTargetIndex - 1);
        return feedback;
    }

    private Feedback fail(String reason) {
        stage = Stage.PREPARE; last = -1; hold = -1; moveStart = -1;
        previous = null; initial = null; currentTargetIndex = 1;
        return emit(0, reason);
    }

    public Feedback missing(String reason) {
        if(stage == Stage.SUCCESS) return feedback;
        return fail(reason + ". Repite la seña");
    }

    private double routeX(Sample s) { return reverse ? 1 - s.x : s.x; }

    private boolean inZoneRelative(Sample s, double[] wp) {
        double targetX = wp[0] + offsetX;
        double targetY = wp[1] + offsetY;
        double dx = routeX(s) - targetX;
        double dy = s.y - targetY;
        return Math.hypot(dx, dy) <= ZONE_RADIUS;
    }

    private String postureError(Sample s) {
        if(s == null || s.side == null || s.side.isEmpty() || s.flex.length != 10)
            return "Muestra la mano claramente";
        if(!Double.isFinite(s.x) || !Double.isFinite(s.y) || !Double.isFinite(s.palm))
            return "Lectura no válida";
        if(s.palm < .05) return "Acerca la mano";
        if(s.palm > .45) return "Aleja la mano";
        return null;
    }

    public Feedback update(long time, Sample s) {
        if(stage == Stage.SUCCESS) return feedback;

        String error = postureError(s);
        // --- INICIO NUEVO: Tolerancia de oclusión ---
        if(error != null) {
            // Si hay un error de postura pero YA estamos moviéndonos, damos un periodo de gracia
            // de 1.2 segundos (MAX_GAP_MS) antes de cancelar.
            if (stage == Stage.MOVE && (time - last < MAX_GAP_MS)) {
                return feedback; // Ignoramos el error temporal y mantenemos el progreso
            }
            return fail(error + ". Repite");
        }
        // --- FIN NUEVO ---

        if(last >= 0 && (time <= last || time - last > MAX_GAP_MS))
            return fail("Se interrumpió el seguimiento. Repite");

        // --- LA SOLUCIÓN AL PROBLEMA DE LA CURVA ---
        if(initial != null) {
            if(!initial.side.equals(s.side)) return fail("Cambiaste de mano. Repite");

            // SOLO evaluamos la postura estricta de todos los dedos cuando estás QUIETO en INICIO.
            // Si ya estás en la etapa MOVE (moviéndote), ignoramos lo que MediaPipe crea
            // ver en los otros dedos, porque sabemos que la cámara se confunde.
            if (stage == Stage.PREPARE || stage == Stage.HOLD_START) {
                for(int i = 0; i < 10; i++) {
                    if(Math.abs(s.flex[i] - initial.flex[i]) > 45) { // Regresamos a 45 para que sea preciso al inicio
                        return fail("Perdiste la forma de la letra. Repite");
                    }
                }
            }
        }

        last = time;

        switch(stage) {
            case PREPARE:
                stage = Stage.HOLD_START;
                hold = time;
                initial = s;
                previous = s;
                return emit(0, "Mantén la postura...");

            case HOLD_START:
                if(Math.hypot(s.x - previous.x, s.y - previous.y) > 0.04) {
                    hold = time;
                    initial = s;
                }
                previous = s;

                if(time - hold >= 500) {
                    stage = Stage.MOVE;
                    moveStart = time;
                    currentTargetIndex = 1;

                    offsetX = routeX(s) - waypoints[0][0];
                    offsetY = s.y - waypoints[0][1];

                    return emit(10, "¡Realiza el trazo de la seña!");
                }
                return emit((int)Math.min(9, (time - hold) * 10 / 500), "Quédate quieto para anclar el punto");

            case MOVE:
                if(time - moveStart > 8000) return fail("Trazo demasiado lento. Repite");

                if(inZoneRelative(s, waypoints[currentTargetIndex])) {
                    currentTargetIndex++;

                    if(currentTargetIndex >= waypoints.length) {
                        stage = Stage.HOLD_END; hold = time;
                        return emit(85, "¡Bien! Quédate ahí un momento");
                    }
                }

                int baseProgress = 10;
                int routeProgress = (int)(75.0 * (currentTargetIndex - 1) / (waypoints.length - 1));
                return emit(baseProgress + routeProgress, "Trazando... (" + currentTargetIndex + "/" + waypoints.length + ")");

            case HOLD_END:
                if(!inZoneRelative(s, waypoints[waypoints.length - 1])) {
                    stage = Stage.MOVE; hold = -1; currentTargetIndex = waypoints.length - 1;
                    return emit(80, "Vuelve al final del trazo");
                }
                if(time - hold >= 300) {
                    stage = Stage.SUCCESS;
                    return emit(100, "¡Letra validada con éxito!");
                }
                return emit(85 + (int)Math.min(14, (time - hold) * 15 / 300), "Casi...");

            default: return feedback;
        }
    }

    public static double flexion(double[] a, double[] b, double[] c) {
        double dot=0, aa=0, cc=0;
        for(int i=0; i<3; i++) {
            double u = a[i]-b[i], v = c[i]-b[i]; dot += u*v; aa += u*u; cc += v*v;
        }
        if(aa < 1e-12 || cc < 1e-12) return Double.NaN;
        double cosine = Math.max(-1, Math.min(1, dot / Math.sqrt(aa*cc)));
        return 180 - Math.toDegrees(Math.acos(cosine));
    }

    public static double[] fingerFlexions(double[][] p) {
        if(p.length != 21) throw new IllegalArgumentException("Se necesitan 21 puntos");
        int[][] joints = {{1,2,3},{2,3,4},{5,6,7},{6,7,8},{9,10,11},
                {10,11,12},{13,14,15},{14,15,16},{17,18,19},{18,19,20}};
        double[] result = new double[10];
        for(int i=0; i<joints.length; i++) result[i] = flexion(p[joints[i][0]], p[joints[i][1]], p[joints[i][2]]);
        return result;
    }
}