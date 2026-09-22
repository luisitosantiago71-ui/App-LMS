package com.mechrobotix.aprendels;

import java.util.*;

/** Postura A -> curva observada -> postura B. No compara coordenadas del recorrido. */
public final class JPracticeEngine {
    public enum Stage { START, HOLD, MOVE, END, SUCCESS }
    public enum Tolerance {
        STRICT("Precisa", .40,.12,550,500,10000,35,.27),
        NORMAL("Normal", .48,.18,450,450,15000,28,.22),
        FLEXIBLE("Flexible", .55,.22,400,400,20000,23,.18);
        public final String label;
        final double shape,stability,minTurn,minBend;
        final long initialMs,finalMs,durationMs;
        Tolerance(String l,double s,double stable,long a,long b,long duration,double turn,double bend){
            label=l;shape=s;stability=stable;initialMs=a;finalMs=b;durationMs=duration;
            minTurn=Math.toRadians(turn);minBend=bend;
        }
    }
    public static final class Sample {
        public final double[][] points;
        public final double[] flex;
        public final double palm;
        public final String side;
        public Sample(double[][] points,double[] flex,String side) {
            this.points=new double[points==null?0:points.length][];
            for(int i=0;i<this.points.length;i++)this.points[i]=points[i]==null?new double[0]:points[i].clone();
            this.flex=flex==null?new double[0]:flex.clone();this.side=side;
            palm=this.points.length==21 && this.points[0].length==2 && this.points[9].length==2?distance(this.points[0],this.points[9]):0;
        }
        public boolean valid(){
            if(points.length!=21 || flex.length!=10 || side==null || side.isEmpty() || !Double.isFinite(palm) || palm<2)return false;
            for(double[] p:points)if(p.length!=2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1]))return false;
            for(double f:flex)if(!Double.isFinite(f))return false;
            return true;
        }
    }
    public static double distance(double[] a,double[] b){return Math.hypot(a[0]-b[0],a[1]-b[1]);}
    // Fixed track for the whole attempt: switching points would create an artificial curve.
    private static final int[][] TRACKS={{0,5,9,13,17},{13,17},{5,9},{20}};
    private final List<Sample> reference;
    private final int track;
    private final double referenceSign;
    private Tolerance tolerance=Tolerance.NORMAL;
    private Stage stage=Stage.START;
    private boolean mirrored,uncertain,tipReliable=true,curveSeen;
    private Sample anchor,previous,stable;
    private long last=-1,hold=-1,began=-1,lostSince=-1;
    private final List<double[]> route=new ArrayList<>();
    private double[] filtered;
    private String message="Forma la postura inicial A";
    public JPracticeEngine(List<Sample> samples){
        if(samples==null || samples.size()<12)throw new IllegalArgumentException("Referencia incompleta");
        reference=new ArrayList<>();
        for(Sample s:samples){if(s==null || !s.valid())throw new IllegalArgumentException("Referencia no válida");reference.add(new Sample(s.points,s.flex,s.side));}
        int chosen=-1;double sign=0;
        for(int k=0;k<TRACKS.length;k++){
            List<double[]> path=new ArrayList<>();double[] f=null;
            for(Sample s:reference){double[] p=position(s,k);p[0]/=reference.get(0).palm;p[1]/=reference.get(0).palm;
                f=smooth(f,p);append(path,f);}
            Curve c=curve(path);
            if(c!=null && c.length>=.8 && c.bend>=.18 && c.turn>=Math.toRadians(23) && c.turn<Math.toRadians(165)){
                chosen=k;sign=c.sign;break;
            }
        }
        if(chosen<0)throw new IllegalArgumentException("No se observa una curva clara. Revisa el tramo completo del video");
        track=chosen;referenceSign=sign;
    }
    private static double[] position(Sample s,int track){double x=0,y=0;for(int i:TRACKS[track]){x+=s.points[i][0];y+=s.points[i][1];}return new double[]{x/TRACKS[track].length,y/TRACKS[track].length};}
    private static double[] smooth(double[] old,double[] p){return old==null?p.clone():new double[]{old[0]*.45+p[0]*.55,old[1]*.45+p[1]*.55};}
    private static void append(List<double[]> path,double[] p){if(path.isEmpty() || distance(path.get(path.size()-1),p)>=.08)path.add(p.clone());}
    private static final class Curve {double length,bend,turn,sign;}
    /** Arc-length resampling suppresses dependence on execution speed. No per-frame targets. */
    private static Curve curve(List<double[]> path){
        if(path.size()<5)return null;
        double[] lengths=new double[path.size()];
        for(int i=1;i<path.size();i++)lengths[i]=lengths[i-1]+distance(path.get(i-1),path.get(i));
        double total=lengths[lengths.length-1];if(total<.1)return null;
        double[][] q=new double[5][2];int j=1;
        for(int k=0;k<5;k++){
            double wanted=total*k/4;
            while(j<path.size()-1 && lengths[j]<wanted)j++;
            double u=(wanted-lengths[j-1])/Math.max(1e-9,lengths[j]-lengths[j-1]);
            for(int d=0;d<2;d++)q[k][d]=path.get(j-1)[d]+u*(path.get(j)[d]-path.get(j-1)[d]);
        }
        double ax=q[2][0]-q[0][0],ay=q[2][1]-q[0][1],bx=q[4][0]-q[2][0],by=q[4][1]-q[2][1];
        if(Math.hypot(ax,ay)<.15 || Math.hypot(bx,by)<.15)return null;
        double signed=Math.atan2(ax*by-ay*bx,ax*bx+ay*by);
        double dx=q[4][0]-q[0][0],dy=q[4][1]-q[0][1],chord=Math.hypot(dx,dy);
        if(chord<.25)return null;
        double bend=0;
        for(int k=1;k<4;k++)bend=Math.max(bend,Math.abs(dx*(q[k][1]-q[0][1])-dy*(q[k][0]-q[0][0]))/chord);
        Curve c=new Curve();c.length=total;c.bend=bend;c.turn=Math.abs(signed);c.sign=Math.signum(signed);return c;
    }
    public List<Sample> getReference(){return Collections.unmodifiableList(reference);}
    public Stage getStage(){return stage;}
    public int getIndex(){return stage==Stage.SUCCESS?reference.size()-1:0;}
    public Sample getAnchor(){return anchor;}
    public String getMessage(){return message;}
    public boolean isMirrored(){return mirrored;}
    public boolean isTipReliable(){return tipReliable && !uncertain;}
    public boolean isCurveSeen(){return curveSeen;}
    public String getTrackingDescription(){return track==3?"Meñique (requiere lectura continua)":"Palma y bases de los dedos";}
    public Tolerance getTolerance(){return tolerance;}
    public void setTolerance(Tolerance t){if(t==null)throw new IllegalArgumentException("Tolerancia requerida");tolerance=t;reset();}
    public void setMirrored(boolean m){mirrored=m;reset();}
    public int getProgress(){return stage==Stage.SUCCESS?100:stage==Stage.END?90:curveSeen?70:stage==Stage.MOVE?35:stage==Stage.HOLD?10:0;}
    public void reset(){stage=Stage.START;anchor=null;previous=null;stable=null;last=-1;hold=-1;began=-1;lostSince=-1;route.clear();filtered=null;curveSeen=false;uncertain=false;tipReliable=true;message="Forma la postura inicial A";}
    private void restart(String reason){reset();message=reason+". Vuelve a A";}
    // Compatibility for callers without a timestamp: fail closed.
    public void missing(String reason){if(stage!=Stage.SUCCESS)restart(reason);}
    public void missing(long time,String reason){
        if(stage==Stage.SUCCESS)return;
        if(lostSince<0)lostSince=time;
        uncertain=true;hold=-1;stable=null;previous=null;
        // Never join unseen samples into a curve. Previously verified evidence is retained briefly.
        route.clear();filtered=null;
        message=reason+". Esperando lectura clara";
        if(time-lostSince>=700)restart("No pude observar una parte necesaria");
    }
    private double shape(Sample s,Sample t){
        double sum=0;for(int i=1;i<21;i++)sum+=Math.hypot(
                (s.points[i][0]-s.points[0][0])/s.palm-(mirrored?-1:1)*(t.points[i][0]-t.points[0][0])/t.palm,
                (s.points[i][1]-s.points[0][1])/s.palm-(t.points[i][1]-t.points[0][1])/t.palm);
        return sum/20;
    }
    private boolean posture(Sample s,Sample t){
        if(!tipReliable || shape(s,t)>tolerance.shape)return false;
        double sum=0;for(int i=0;i<10;i++){double d=Math.abs(s.flex[i]-t.flex[i]);if(d>(i>=8?45:60))return false;sum+=d;}return sum/10<=32;
    }
    private boolean tipPlausible(Sample s){
        double bone=distance(s.points[19],s.points[20])/s.palm;
        if(bone<.045 || bone>.8)return false;
        if(s.flex[8]<45 && s.flex[9]<45 && distance(s.points[17],s.points[20])/s.palm<.28)return false;
        if(previous!=null){
            double dx=(s.points[20][0]-s.points[17][0])/s.palm-(previous.points[20][0]-previous.points[17][0])/previous.palm;
            double dy=(s.points[20][1]-s.points[17][1])/s.palm-(previous.points[20][1]-previous.points[17][1])/previous.palm;
            if(Math.hypot(dx,dy)>.7 && distance(position(s,0),position(previous,0))/s.palm<.2)return false;
        }
        return true;
    }
    public void update(long time,Sample s){
        if(stage==Stage.SUCCESS)return;
        if(s==null || !s.valid()){missing(time,"No pude leer la mano");return;}
        if(last>=0 && time<=last){restart("Lectura fuera de orden");return;}
        if((last>=0 && time-last>700) || (lostSince>=0 && time-lostSince>=700)){restart("Se perdió la continuidad");return;}
        if(anchor!=null && !anchor.side.equals(s.side)){restart("Cambió la mano detectada");return;}
        tipReliable=tipPlausible(s);
        if(previous!=null && distance(position(previous,0),position(s,0))/previous.palm>.8){missing(time,"Salto de detección de la palma");return;}
        if((stage==Stage.MOVE || stage==Stage.END) && track==3 && !tipReliable){missing(time,"Meñique dudoso; no puedo confirmar la curva");return;}
        last=time;uncertain=false;lostSince=-1;
        if(stage==Stage.START || stage==Stage.HOLD){
            if(!posture(s,reference.get(0))){hold=-1;stable=null;stage=Stage.START;message=tipReliable?"Ajusta la postura A; usa Espejo si corresponde":"No pude observar claramente el meñique";previous=s;return;}
            if(stable==null || hold<0 || distance(position(stable,0),position(s,0))/s.palm>tolerance.stability){stable=s;hold=time;}
            stage=Stage.HOLD;message="Mantén la postura A";
            if(time-hold>=tolerance.initialMs){anchor=s;began=time;stage=Stage.MOVE;hold=-1;stable=null;route.clear();filtered=null;}
            previous=s;return;
        }
        if(time-began>tolerance.durationMs){restart("Se agotó el tiempo");return;}
        if(!curveSeen){
            double[] p=position(s,track);p[0]/=anchor.palm;p[1]/=anchor.palm;
            filtered=smooth(filtered,p);append(route,filtered);
            if(route.size()>500){restart("Movimiento demasiado largo");return;}
            Curve c=curve(route);
            if(c!=null && c.length>=.8 && c.length<=10 && c.bend>=tolerance.minBend && c.turn>=tolerance.minTurn && c.turn<Math.toRadians(165)
                    && c.sign==referenceSign*(mirrored?-1:1) && time-began>=600)curveSeen=true;
        }
        previous=s;
        if(!curveSeen){message=tipReliable?"Realiza la curva; no hay un recorrido fijo":"Meñique dudoso: observando la palma";return;}
        if(!posture(s,reference.get(reference.size()-1))){stage=Stage.MOVE;hold=-1;stable=null;message=tipReliable?"Curva observada. Forma la postura final B":"Curva observada; muestra el meñique para verificar B";return;}
        if(stable==null || hold<0 || distance(position(stable,0),position(s,0))/s.palm>tolerance.stability){stable=s;hold=time;}
        stage=Stage.END;message="Mantén la postura B";
        if(time-hold>=tolerance.finalMs){stage=Stage.SUCCESS;message="¡Postura A, curva y postura B completadas!";}
    }
}
