package com.mechrobotix.aprendels;

/** Practice flow: a usable reference and a finished demonstration are required before camera validation. */
public final class MotionLessonFlow {
    public enum Phase { LOADING, DEMONSTRATION, PRACTICE, COMPLETE, ERROR }
    private Phase phase=Phase.LOADING;
    private boolean referenceReady;
    public Phase getPhase(){return phase;}
    public boolean isDemonstrating(){return phase==Phase.DEMONSTRATION;}
    public boolean isPracticing(){return phase==Phase.PRACTICE;}
    public void loading(){phase=Phase.LOADING;referenceReady=false;}
    public void demonstrate(boolean ready){referenceReady=ready;phase=Phase.DEMONSTRATION;}
    public boolean demoFinished(){
        if(phase!=Phase.DEMONSTRATION)return false;
        phase=referenceReady?Phase.PRACTICE:Phase.ERROR;
        return true;
    }
    public boolean complete(){
        if(phase!=Phase.PRACTICE)return false;
        phase=Phase.COMPLETE;return true;
    }
    public void fail(){phase=Phase.ERROR;referenceReady=false;}
}
