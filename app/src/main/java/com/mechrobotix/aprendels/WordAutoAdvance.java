package com.mechrobotix.aprendels;

/** Una sola transición después del éxito. El reloj y la actividad se controlan desde la UI. */
public final class WordAutoAdvance {
    private final long delayMs;
    private long completedAt=-1;
    private boolean consumed=false;
    public WordAutoAdvance(long delayMs) {
        if(delayMs<0)throw new IllegalArgumentException("La espera no puede ser negativa");
        this.delayMs=delayMs;
    }
    public void completed(long now) {
        if(completedAt<0 && !consumed)completedAt=now;
    }
    public boolean take(long now) {
        if(consumed || completedAt<0 || now-completedAt<delayMs)return false;
        consumed=true;return true;
    }
    public void reset(){completedAt=-1;consumed=false;}
}
