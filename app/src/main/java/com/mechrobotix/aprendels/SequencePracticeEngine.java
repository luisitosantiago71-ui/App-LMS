package com.mechrobotix.aprendels;

import java.util.*;

/** Ordered key poses plus relative motion; independent of the J-specific curve detector. */
public final class SequencePracticeEngine {
    private final List<JPracticeEngine.Sample> keys;
    private boolean mirrored;
    private JPracticeEngine.Tolerance tolerance=JPracticeEngine.Tolerance.NORMAL;
    private JPracticeEngine.Stage stage=JPracticeEngine.Stage.START;
    private JPracticeEngine.Sample anchor,stable,checkpoint;
    private long last=-1,hold=-1,began=-1,lostSince=-1;
    private int target;
    private String message="1/3 · Forma la postura inicial del video";
    public SequencePracticeEngine(List<JPracticeEngine.Sample> samples){
        if(samples==null || samples.size()<12)throw new IllegalArgumentException("Referencia incompleta");
        for(JPracticeEngine.Sample s:samples)if(s==null || !s.valid())throw new IllegalArgumentException("Lectura no válida");
        // Resample by combined hand translation and shape change, not by video speed.
        double[] length=new double[samples.size()];
        for(int i=1;i<samples.size();i++)length[i]=length[i-1]+change(samples.get(i-1),samples.get(i));
        double total=length[length.length-1];
        double excursion=0;
        for(JPracticeEngine.Sample s:samples)excursion=Math.max(excursion,change(samples.get(0),s));
        if(excursion<.16)throw new IllegalArgumentException("El movimiento es demasiado pequeño para distinguirlo del ruido de detección");
        if(total<.40)throw new IllegalArgumentException("El tramo no muestra suficiente movimiento o cambio de postura");
        keys=new ArrayList<>();keys.add(samples.get(0));int previous=0;
        for(int k=1;k<7;k++){
            int j=previous+1;while(j<samples.size()-1 && length[j]<total*k/7)j++;
            if(j<samples.size()-1){keys.add(samples.get(j));previous=j;}
        }
        keys.add(samples.get(samples.size()-1));
        if(keys.size()<4)throw new IllegalArgumentException("Movimiento demasiado brusco o referencia insuficiente");
    }
    private static double change(JPracticeEngine.Sample a,JPracticeEngine.Sample b){
        double sum=0,tip=0,flex=0;
        for(int i=0;i<21;i++)sum+=JPracticeEngine.distance(a.points[i],b.points[i])/a.palm;
        for(int i:new int[]{4,8,12,16,20})tip=Math.max(tip,JPracticeEngine.distance(a.points[i],b.points[i])/a.palm);
        for(int i=0;i<10;i++)flex=Math.max(flex,Math.abs(a.flex[i]-b.flex[i])/180.0);
        return Math.max(sum/21,Math.max(tip*.35,flex));
    }
    public void setMirrored(boolean value){mirrored=value;reset();}
    public void setTolerance(JPracticeEngine.Tolerance value){tolerance=value;reset();}
    public boolean isMirrored(){return mirrored;}
    public JPracticeEngine.Stage getStage(){return stage;}
    public String getMessage(){return message;}
    public int getProgress(){return stage==JPracticeEngine.Stage.SUCCESS?100:stage==JPracticeEngine.Stage.END?90:target*85/keys.size();}
    public void reset(){stage=JPracticeEngine.Stage.START;anchor=null;stable=null;checkpoint=null;last=hold=began=lostSince=-1;target=0;message="1/3 · Forma la postura inicial del video";}
    private int level(){return tolerance.ordinal();}
    private long gap(){return new long[]{350,500,800}[level()];}
    private long holdMs(){return new long[]{550,450,350}[level()];}
    private double stability(){return new double[]{.15,.20,.28}[level()];}
    public void missing(long time,String reason){
        if(stage==JPracticeEngine.Stage.SUCCESS)return;
        hold=-1;stable=null;
        if(lostSince<0)lostSince=time;
        if(time-lostSince>gap()){reset();message=reason+". Vuelve a la postura inicial";}
        else message=reason+". Recupera la mano para continuar";
    }
    private boolean matches(JPracticeEngine.Sample actual,JPracticeEngine.Sample reference,boolean moving){
        double sum=0,flex=0;double direction=mirrored?-1:1;
        for(int i=1;i<21;i++)sum+=Math.hypot(
            (actual.points[i][0]-actual.points[0][0])/actual.palm-direction*(reference.points[i][0]-reference.points[0][0])/reference.palm,
            (actual.points[i][1]-actual.points[0][1])/actual.palm-(reference.points[i][1]-reference.points[0][1])/reference.palm);
        for(int i=0;i<10;i++){double d=Math.abs(actual.flex[i]-reference.flex[i]);if(d>new double[]{50,65,85}[level()])return false;flex+=d;}
        double margin=new double[]{.36,.48,.64}[level()];
        if(sum/20>margin || flex/10>new double[]{24,34,44}[level()])return false;
        if(moving){
            JPracticeEngine.Sample first=keys.get(0);
            double ax=(actual.points[0][0]-anchor.points[0][0])/anchor.palm;
            double ay=(actual.points[0][1]-anchor.points[0][1])/anchor.palm;
            double rx=direction*(reference.points[0][0]-first.points[0][0])/first.palm;
            double ry=(reference.points[0][1]-first.points[0][1])/first.palm;
            if(Math.hypot(ax-rx,ay-ry)>new double[]{.50,.80,1.05}[level()])return false;
        }
        return true;
    }
    public void update(long time,JPracticeEngine.Sample sample){
        if(stage==JPracticeEngine.Stage.SUCCESS)return;
        if(sample==null || !sample.valid()){missing(time,"No hay una lectura válida");return;}
        if(last>=0 && (time<=last || time-last>gap())){reset();message="Se perdió la continuidad. Vuelve al inicio";return;}
        if(anchor!=null && !anchor.side.equals(sample.side)){reset();message="Cambió la mano. Vuelve al inicio";return;}
        last=time;lostSince=-1;
        if(stage==JPracticeEngine.Stage.START || stage==JPracticeEngine.Stage.HOLD){
            if(!matches(sample,keys.get(0),false)){stage=JPracticeEngine.Stage.START;hold=-1;stable=null;message="Ajusta la postura inicial o activa Espejo";return;}
            if(stable==null || JPracticeEngine.distance(stable.points[0],sample.points[0])/sample.palm>stability()){stable=sample;hold=time;}
            stage=JPracticeEngine.Stage.HOLD;message="1/3 · Mantén la postura inicial";
            if(time-hold>=holdMs()){anchor=sample;checkpoint=sample;began=time;target=1;stage=JPracticeEngine.Stage.MOVE;hold=-1;stable=null;}
            return;
        }
        if(time-began>new long[]{15000,20000,25000}[level()]){reset();message="Tiempo agotado. Vuelve al inicio";return;}
        if(!matches(sample,keys.get(target),true)){hold=-1;message="Imita el movimiento en orden: etapa "+target+" de "+(keys.size()-1);return;}
        if(target<keys.size()-1){
            // Require observed change between checkpoints: time alone must never validate motion.
            double expected=change(keys.get(target-1),keys.get(target));
            if(change(checkpoint,sample)<Math.max(.025,expected*.40)){hold=-1;message="Realiza el movimiento; mantener la mano quieta no completa la secuencia";return;}
            // Require two fresh frames near each ordered key pose.
            if(hold<0){hold=time;return;}
            if(time-hold<new long[]{80,60,40}[level()])return;
            checkpoint=sample;target++;hold=-1;stable=null;message="2/3 · Continúa el recorrido";return;
        }
        double expected=change(keys.get(target-1),keys.get(target));
        if(change(checkpoint,sample)<Math.max(.025,expected*.40)){hold=-1;return;}
        stage=JPracticeEngine.Stage.END;
        if(stable==null || change(stable,sample)>stability()){stable=sample;hold=time;}
        if(hold<0)hold=time;
        message="3/3 · Mantén la postura final";
        if(time-hold>=holdMs() && time-began>=800){stage=JPracticeEngine.Stage.SUCCESS;message="¡Secuencia de referencia completada!";}
    }
}
