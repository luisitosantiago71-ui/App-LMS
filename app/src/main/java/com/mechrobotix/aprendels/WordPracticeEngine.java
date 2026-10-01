package com.mechrobotix.aprendels;

import java.util.*;

/** Inicio -> hitos de movimiento EN ORDEN -> final. Una o dos manos por hito.
 * No reconoce lenguaje libre ni expresiones faciales. Compara con la referencia de la lección.
 * Independiente de Android para poder probar referencias y tolerancias en una computadora.
 */
public final class WordPracticeEngine {
    public static final class Frame {
        public final long timeMs;
        public final Map<String,JPracticeEngine.Sample> hands;
        public Frame(long time,Map<String,JPracticeEngine.Sample> hands) {
            timeMs=time;this.hands=Collections.unmodifiableMap(new LinkedHashMap<>(hands));
        }
        public boolean valid(){return !hands.isEmpty() && hands.size()<=2 && hands.values().stream().allMatch(JPracticeEngine.Sample::valid);}
    }
    public enum Stage { START, MOVE, END, SUCCESS }
    private final List<Frame> keys;
    private final WordTolerance tolerance;
    private final String primary;
    private final boolean mirror;
    private Stage stage=Stage.START;
    private String message="1/3 · Imita la postura inicial del video";
    private boolean swap;
    private int target=0;
    private long last=-1,hold=-1,began=-1,lost=-1;
    private Frame anchor,checkpoint,stable;
    public WordPracticeEngine(List<Frame> keys,WordTolerance tolerance,boolean mirror) {
        if(keys==null || keys.size()<4)throw new IllegalArgumentException("Referencia de palabra incompleta");
        for(Frame f:keys)if(f==null || !f.valid())throw new IllegalArgumentException("Hito sin manos válidas");
        this.keys=new ArrayList<>(keys);this.tolerance=tolerance;this.mirror=mirror;tolerance.validate();
        primary=keys.get(0).hands.keySet().iterator().next();
    }
    /** Evita que una palma de perfil se reduzca a casi cero por perspectiva. */
    public static double palmSize(JPracticeEngine.Sample s) {
        double size=0;
        for(int[] pair:new int[][]{{0,5},{0,9},{0,13},{0,17},{5,17}})
            size=Math.max(size,JPracticeEngine.distance(s.points[pair[0]],s.points[pair[1]]));
        return Math.max(2,size);
    }
    public Stage getStage(){return stage;}
    public String getMessage(){return message;}
    public int getProgress(){return stage==Stage.SUCCESS?100:stage==Stage.END?92:target*90/(keys.size()-1);}
    public int getTarget(){return target;}
    public int getKeyCount(){return keys.size();}
    public void reset(){stage=Stage.START;target=0;last=hold=began=lost=-1;anchor=checkpoint=stable=null;message="1/3 · Imita la postura inicial del video";}
    private String side(String reference){return swap?(reference.equals("Left")?"Right":"Left"):reference;}
    private double direction(){return mirror?-1.0:1.0;}
    private JPracticeEngine.Sample hand(Frame f,String ref){return f.hands.get(side(ref));}
    public void missing(long time,String reason){
        if(stage==Stage.SUCCESS)return;
        hold=-1;stable=null;
        if(lost<0)lost=time;
        if(time-lost>tolerance.lostGraceMs){reset();message=reason+". Comienza de nuevo";}
        else message=reason+". Recupera la postura para continuar";
    }
    private double shape(JPracticeEngine.Sample a,JPracticeEngine.Sample r) {
        double sum=0,flex=0,max=0;
        for(int i=1;i<21;i++)sum+=Math.hypot(
            (a.points[i][0]-a.points[0][0])/palmSize(a)-direction()*(r.points[i][0]-r.points[0][0])/palmSize(r),
            (a.points[i][1]-a.points[0][1])/palmSize(a)-(r.points[i][1]-r.points[0][1])/palmSize(r));
        for(int i=0;i<10;i++){double d=Math.abs(a.flex[i]-r.flex[i]);flex+=d;max=Math.max(max,d);}
        return Math.max(sum/20/tolerance.shape,Math.max(flex/10/tolerance.meanFlex,max/tolerance.maxFlex));
    }
    private double score(Frame actual,Frame ref,boolean moving) {
        double score=0;
        JPracticeEngine.Sample r0=keys.get(0).hands.get(primary);
        JPracticeEngine.Sample a0=moving?hand(anchor,primary):hand(actual,primary);
        if(a0==null)return Double.POSITIVE_INFINITY;
        for(Map.Entry<String,JPracticeEngine.Sample> e:ref.hands.entrySet()) {
            JPracticeEngine.Sample a=hand(actual,e.getKey()),r=e.getValue();
            if(a==null)return Double.POSITIVE_INFINITY;
            score=Math.max(score,shape(a,r));
            if(moving || ref.hands.size()==2) {
                double rx=direction()*(r.points[0][0]-r0.points[0][0])/palmSize(r0);
                double ry=(r.points[0][1]-r0.points[0][1])/palmSize(r0);
                double ax=(a.points[0][0]-a0.points[0][0])/palmSize(a0);
                double ay=(a.points[0][1]-a0.points[0][1])/palmSize(a0);
                double margin=tolerance.travel+Math.min(.8,Math.hypot(rx,ry)*tolerance.travelRelative);
                score=Math.max(score,Math.hypot(ax-rx,ay-ry)/margin);
            }
        }
        return score;
    }
    /** Cambio de puntos normalizado por palma; no depende de posición absoluta ni velocidad. */
    public static double change(Frame a,Frame b) {
        double max=0;
        for(String side:a.hands.keySet()) {
            JPracticeEngine.Sample p=a.hands.get(side),q=b.hands.get(side);if(q==null)continue;
            double sum=0;for(int i=0;i<21;i++)sum+=JPracticeEngine.distance(p.points[i],q.points[i])/palmSize(p);
            max=Math.max(max,sum/21);
        }
        return max;
    }
    private boolean motion(Frame actual) {
        Frame before=keys.get(target-1),wanted=keys.get(target);
        double expected=change(before,wanted);
        if(change(checkpoint,actual)<Math.max(tolerance.minMotion,expected*tolerance.minMotionRatio))return false;
        // Comprobar dirección solo cuando hay traslación clara; cambios de dedos no la necesitan.
        for(String refSide:before.hands.keySet()) {
            JPracticeEngine.Sample r=before.hands.get(refSide),s=wanted.hands.get(refSide);
            JPracticeEngine.Sample a=hand(checkpoint,refSide),b=hand(actual,refSide);
            if(s==null || a==null || b==null)continue;
            double rx=direction()*(s.points[0][0]-r.points[0][0])/palmSize(r),ry=(s.points[0][1]-r.points[0][1])/palmSize(r);
            double ax=(b.points[0][0]-a.points[0][0])/palmSize(a),ay=(b.points[0][1]-a.points[0][1])/palmSize(a);
            double rn=Math.hypot(rx,ry),an=Math.hypot(ax,ay);
            if(rn>.60 && (an<.10 || (rx*ax+ry*ay)/(rn*an)<tolerance.directionCosine))return false;
            if(rn>.60) {
                JPracticeEngine.Sample r0=keys.get(0).hands.get(primary),a0=hand(anchor,primary);
                double dx=(b.points[0][0]-a0.points[0][0])/palmSize(a0)-direction()*(r.points[0][0]-r0.points[0][0])/palmSize(r0);
                double dy=(b.points[0][1]-a0.points[0][1])/palmSize(a0)-(r.points[0][1]-r0.points[0][1])/palmSize(r0);
                // No aceptar un giro antes de aproximarse a su vértice.
                if((dx*rx+dy*ry)/(rn*rn)<tolerance.minTravelProgress)return false;
            }
        }
        return true;
    }
    public void update(long time,Frame sample) {
        if(stage==Stage.SUCCESS)return;
        if(last>=0 && time<=last)return; // Un callback repetido no suma tiempo ni progreso.
        if(sample==null || !sample.valid()){missing(time,"Coloca las manos dentro de la cámara");return;}
        if(last>=0 && time-last>tolerance.lostGraceMs){reset();message="Se perdió la continuidad. Repite desde el inicio";return;}
        last=time;
        if(stage==Stage.START) {
            boolean oldSwap=swap;
            swap=false;double direct=score(sample,keys.get(0),false);
            swap=true;double other=score(sample,keys.get(0),false);
            swap=other<direct;
            if(Math.min(direct,other)>1){hold=-1;stable=null;message="1/3 · Ajusta la postura inicial; imita el video como un espejo";return;}
            lost=-1;
            if(stable==null || oldSwap!=swap || change(stable,sample)>tolerance.stability){stable=sample;hold=time;}
            message="1/3 · Bien, mantén un momento el inicio";
            if(time-hold>=tolerance.startHoldMs){anchor=sample;checkpoint=sample;target=1;began=time;hold=-1;stable=null;stage=Stage.MOVE;message="2/3 · Ahora realiza la palabra completa";}
            return;
        }
        if(time-began>tolerance.maxAttemptMs){reset();message="Vamos otra vez: comienza con la postura inicial";return;}
        Frame wanted=keys.get(target);
        if(wanted.hands.keySet().stream().anyMatch(s->hand(sample,s)==null)){
            missing(time,wanted.hands.size()==2?"Esta parte necesita las dos manos":"No se ve la mano de la seña");return;
        }
        // Una oclusión larga también reinicia aunque la otra mano siguiera visible.
        if(lost>=0 && time-lost>tolerance.lostGraceMs){reset();message="Se perdió una mano. Repite desde el inicio";return;}
        lost=-1;
        if(score(sample,wanted,true)>1){hold=-1;stable=null;message="2/3 · Sigue el movimiento del video ("+target+"/"+(keys.size()-1)+")";return;}
        if(stage!=Stage.END && !motion(sample)){hold=-1;message="2/3 · Continúa el movimiento; no basta mantener la mano quieta";return;}
        if(target<keys.size()-1){checkpoint=sample;target++;hold=-1;stable=null;message="2/3 · Bien, continúa la palabra";return;}
        stage=Stage.END;
        if(stable==null || change(stable,sample)>tolerance.stability){stable=sample;hold=time;}
        if(hold<0)hold=time;
        message="3/3 · Mantén un momento la postura final";
        if(time-hold>=tolerance.endHoldMs && time-began>=tolerance.minAttemptMs){stage=Stage.SUCCESS;message="¡Palabra completada!";}
    }
}
