package com.mechrobotix.aprendels;

import java.util.*;

/** Práctica por 3–6 etapas. Posturas en controles; movimiento sin cotejar cada fotograma. */
public final class WordPracticeEngine {
    public static final class Frame {
        public final long timeMs;
        public final Map<String,JPracticeEngine.Sample> hands;
        public Frame(long time,Map<String,JPracticeEngine.Sample> hands) {
            timeMs=time;this.hands=Collections.unmodifiableMap(new LinkedHashMap<>(hands));
        }
        public boolean valid(){return !hands.isEmpty() && hands.size()<=2 && hands.values().stream().allMatch(JPracticeEngine.Sample::valid);}
    }
    public enum Stage { START, MOVE, POSE, END, SUCCESS }
    private final WordTolerance tolerance;
    private final WordStageOptions options;
    private final WordStagePlan plan;
    private final boolean mirror;
    private final List<Frame> route;
    private Frame routeLastMatch;
    private final Map<String,JPracticeEngine.Sample> routeReferenceStarts=new HashMap<>(),routeActualStarts=new HashMap<>();
    private int routeTarget;
    private Stage stage=Stage.START;
    private String message;
    private int target=0,motionSamples=0;
    private boolean swap=false;
    private long last=-1,began=-1,lost=-1,lastMotionTime=-1,lastGood=-1,held=0,lastMatch=-1;
    private Frame anchor,referenceAnchor,motionPrevious,stable;
    private double excursion=0;

    public WordPracticeEngine(List<Frame> keys,WordTolerance tolerance,boolean mirror) {
        this(keys,tolerance,mirror,new WordStageOptions());
    }
    public WordPracticeEngine(List<Frame> keys,WordTolerance tolerance,boolean mirror,WordStageOptions options) {
        if(keys==null || keys.size()<4)throw new IllegalArgumentException("Referencia incompleta");
        for(Frame f:keys)if(f==null || !f.valid())throw new IllegalArgumentException("Referencia sin manos");
        tolerance.validate();options.validate();this.tolerance=tolerance;this.options=options;this.mirror=mirror;
        plan=new WordStagePlan(keys,options.stages);route=Collections.unmodifiableList(new ArrayList<>(keys));reset();
    }
    public static double palmSize(JPracticeEngine.Sample s) {
        double size=0;
        for(int[] pair:new int[][]{{0,5},{0,9},{0,13},{0,17},{5,17}})
            size=Math.max(size,JPracticeEngine.distance(s.points[pair[0]],s.points[pair[1]]));
        return Math.max(2,size);
    }
    public static double change(Frame a,Frame b) {
        double max=0;
        for(String side:a.hands.keySet()) {
            JPracticeEngine.Sample p=a.hands.get(side),q=b.hands.get(side);if(q==null)continue;
            double sum=0;for(int i=0;i<21;i++)sum+=JPracticeEngine.distance(p.points[i],q.points[i])/palmSize(p);
            max=Math.max(max,sum/21);
        }return max;
    }
    public Stage getStage(){return stage;}
    public String getMessage(){return message;}
    public int getProgress(){return stage==Stage.SUCCESS?100:target*100/plan.goals.size();}
    public int getTarget(){return target;}
    public int getKeyCount(){return plan.goals.size();}
    public void reset() {
        stage=Stage.START;target=0;swap=false;
        routeLastMatch=null;routeTarget=1;routeReferenceStarts.clear();routeActualStarts.clear();
        last=began=lost=lastMotionTime=lastGood=lastMatch=-1;
        held=0;motionSamples=0;excursion=0;anchor=referenceAnchor=motionPrevious=stable=null;
        message=label()+"Imita la posición inicial";
    }
    private String label(){return (target+1)+"/"+plan.goals.size()+" · ";}
    private String side(String ref){return swap?(ref.equals("Left")?"Right":"Left"):ref;}
    private JPracticeEngine.Sample hand(Frame f,String ref){return f==null?null:f.hands.get(side(ref));}
    private double direction(){return mirror?-1:1;}
    private boolean hasHands(Frame actual,Frame ref){for(String s:ref.hands.keySet())if(hand(actual,s)==null)return false;return true;}
    private void clearHold(){held=0;lastGood=lastMatch=-1;stable=null;}
    private void mismatch(long time) {
        lastGood=-1;
        if(lastMatch<0 || time-lastMatch>options.matchGraceMs)clearHold();
    }
    private boolean hold(long time,Frame sample,long required) {
        if(stable!=null && change(stable,sample)>tolerance.stability)clearHold();
        if(stable==null)stable=sample;
        if(lastGood>=0)held+=Math.min(150,time-lastGood); // Solo intervalos con postura válida.
        lastGood=lastMatch=time;
        return held>=required;
    }
    public void missing(long time,String reason) {
        if(stage==Stage.SUCCESS)return;
        if(last>=0 && time<=last)return;
        last=time;
        if(lost<0)lost=time;
        clearHold();motionPrevious=null;lastMotionTime=-1; // No contar un salto tras la oclusión.
        if(time-lost>tolerance.lostGraceMs){reset();last=time;message="Se perdió la mano. Repite desde el inicio";}
        else message=label()+reason+"; conserva la etapa y recupera la mano";
    }
    private double posture(Frame actual,Frame ref) {
        if(!hasHands(actual,ref))return Double.POSITIVE_INFINITY;
        double score=0;
        for(var entry:ref.hands.entrySet()) {
            JPracticeEngine.Sample a=hand(actual,entry.getKey()),r=entry.getValue();
            double shape=0,flex=0,worst=0;
            // Distinguir una mano abierta de varios dedos cerrados sin exigir ángulos exactos.
            // Una zona neutral amplia admite variación; el pulgar no se clasifica.
            int mismatches=0;
            for(int finger=1;finger<5;finger++) {
                double rf=(r.flex[finger*2]+r.flex[finger*2+1])/2;
                double af=(a.flex[finger*2]+a.flex[finger*2+1])/2;
                if((rf<25 && af>60) || (rf>50 && af<20))mismatches++;
            }
            if(mismatches>options.maxFingerMismatches)return Double.POSITIVE_INFINITY;
            for(int i=1;i<21;i++)shape+=Math.hypot(
                (a.points[i][0]-a.points[0][0])/palmSize(a)-direction()*(r.points[i][0]-r.points[0][0])/palmSize(r),
                (a.points[i][1]-a.points[0][1])/palmSize(a)-(r.points[i][1]-r.points[0][1])/palmSize(r));
            for(int i=0;i<10;i++){double d=Math.abs(a.flex[i]-r.flex[i]);flex+=d;worst=Math.max(worst,d);}
            score=Math.max(score,Math.max(shape/20/tolerance.shape,Math.max(flex/10/tolerance.meanFlex,worst/tolerance.maxFlex)));
        }
        // Posición relativa entre manos; no impone una coordenada fija en pantalla.
        if(ref.hands.size()==2) {
            String first=ref.hands.keySet().iterator().next();
            JPracticeEngine.Sample a0=hand(actual,first),r0=ref.hands.get(first);
            for(String s:ref.hands.keySet()) {
                JPracticeEngine.Sample a=hand(actual,s),r=ref.hands.get(s);
                double dx=(a.points[0][0]-a0.points[0][0])/palmSize(a0)-direction()*(r.points[0][0]-r0.points[0][0])/palmSize(r0);
                double dy=(a.points[0][1]-a0.points[0][1])/palmSize(a0)-(r.points[0][1]-r0.points[0][1])/palmSize(r0);
                score=Math.max(score,Math.hypot(dx,dy)/(tolerance.travel+1));
            }
        }return score;
    }
    private void observeMotion(long time,Frame sample) {
        if(anchor==null)return;
        excursion=Math.max(excursion,change(anchor,sample));
        if(motionPrevious==null){motionPrevious=sample;lastMotionTime=time;return;}
        if(time-lastMotionTime<60)return;
        if(change(motionPrevious,sample)>=Math.max(options.motionNoise,tolerance.minMotion)) {
            motionSamples++;motionPrevious=sample;lastMotionTime=time;
        }
    }
    /** Solo magnitud/dirección y una región amplia; NO forma de dedos durante desplazamiento. */
    private boolean movement(Frame actual,Frame ref,boolean requireSamples) {
        if(!hasHands(actual,ref))return false;
        double expected=change(referenceAnchor,ref);
        double required=Math.max(options.minDisplacement,Math.min(1.2,expected*tolerance.minMotionRatio));
        if(requireSamples && (motionSamples<options.motionSamples || excursion<required))return false;
        for(String s:ref.hands.keySet()) {
            JPracticeEngine.Sample r0=referenceAnchor.hands.get(s),r=ref.hands.get(s),a0=hand(anchor,s),a=hand(actual,s);
            if(r0==null || a0==null)continue;
            double rx=direction()*(r.points[0][0]-r0.points[0][0])/palmSize(r0),ry=(r.points[0][1]-r0.points[0][1])/palmSize(r0);
            double ax=(a.points[0][0]-a0.points[0][0])/palmSize(a0),ay=(a.points[0][1]-a0.points[0][1])/palmSize(a0);
            double rn=Math.hypot(rx,ry),an=Math.hypot(ax,ay);
            if(rn>.6) {
                if(an<.08 || (rx*ax+ry*ay)/(rn*an)<tolerance.directionCosine)return false;
                if((rx*ax+ry*ay)/(rn*rn)<tolerance.minTravelProgress)return false;
            }
            if(Math.hypot(ax-rx,ay-ry)>tolerance.travel+rn*tolerance.travelRelative)return false;
        }return true;
    }
    /** Recorrido ordenado independiente de las anclas flexibles de las etapas visibles.
     * Normaliza respecto al inicio para que aprobar una etapa antes no acorte la siguiente.
     * Solo se usa cuando pathTolerance > 0; no sigue el reloj del video.
     */
    private void startRoute(Frame actual) {
        routeLastMatch=actual;
        for(String side:route.get(0).hands.keySet()) {
            routeReferenceStarts.put(side,route.get(0).hands.get(side));
            routeActualStarts.put(side,hand(actual,side));
        }
    }
    private boolean routeReached(Frame goal) {
        return routeTarget>=route.size() || route.get(routeTarget).timeMs>goal.timeMs;
    }
    private void observeRoute(Frame actual) {
        if(options.pathTolerance<=0 || routeLastMatch==null)return;
        while(routeTarget<route.size()) {
            Frame ref=route.get(routeTarget);
            if(!hasHands(actual,ref))return;
            // Una mano nueva obtiene su propia ancla al entrar en el recorrido.
            for(String side:ref.hands.keySet())if(!routeReferenceStarts.containsKey(side)) {
                routeReferenceStarts.put(side,ref.hands.get(side));
                routeActualStarts.put(side,hand(actual,side));
            }
            if(!routeMatches(actual,ref))return;
            // Un mismo cuadro inmóvil no puede consumir varios hitos distintos.
            // Los hitos casi iguales de una postura sostenida sí pueden agruparse.
            if(change(route.get(routeTarget-1),ref)>=options.minDisplacement &&
               change(routeLastMatch,actual)<Math.max(options.motionNoise,tolerance.minMotion))return;
            routeLastMatch=actual;routeTarget++;
        }
    }
    private boolean routeMatches(Frame actual,Frame ref) {
        if(!hasHands(actual,ref))return false;
        for(String side:ref.hands.keySet()) {
            // Perfil opcional: todas las manos usan el origen inicial de la mano principal.
            // Evita anclar una mano nueva a una fase anterior del movimiento.
            String origin=options.sharedRouteOrigin?route.get(0).hands.keySet().iterator().next():side;
            var r0=routeReferenceStarts.get(origin);var a0=routeActualStarts.get(origin);
            var r=ref.hands.get(side);var a=hand(actual,side);
            if(r0==null || a0==null)return false;
            double rx=direction()*(r.points[0][0]-r0.points[0][0])/palmSize(r0);
            double ry=(r.points[0][1]-r0.points[0][1])/palmSize(r0);
            double ax=(a.points[0][0]-a0.points[0][0])/palmSize(a0);
            double ay=(a.points[0][1]-a0.points[0][1])/palmSize(a0);
            if(Math.hypot(ax-rx,ay-ry)>options.pathTolerance)return false;
        }
        return true;
    }
    private void advance(long time,Frame actual,Frame ref) {
        anchor=actual;referenceAnchor=ref;motionPrevious=actual;lastMotionTime=time;motionSamples=0;excursion=0;clearHold();
        target++;lost=-1;
        if(target==plan.goals.size()){stage=Stage.SUCCESS;message="¡Palabra completada!";return;}
        var kind=plan.goals.get(target).kind;
        stage=kind==WordStagePlan.Kind.END?Stage.END:kind==WordStagePlan.Kind.POSE?Stage.POSE:Stage.MOVE;
        message=label()+(stage==Stage.END?"Llega a la posición final":stage==Stage.POSE?"Cambia la posición de dedos y manos":"Continúa el desplazamiento del video");
    }
    public void update(long time,Frame sample) {
        if(stage==Stage.SUCCESS || (last>=0 && time<=last))return;
        if(sample==null || !sample.valid()){missing(time,"Coloca las manos en la cámara");return;}
        if(last>=0 && time-last>tolerance.lostGraceMs){reset();last=time;message="Se perdió la continuidad. Repite desde el inicio";return;}
        if(lost>=0 && time-lost>tolerance.lostGraceMs){reset();last=time;message="Se perdió la mano. Repite desde el inicio";return;}
        var goal=plan.goals.get(target);
        if(stage==Stage.START) {
            boolean previousSwap=swap,chosen=false;double best=Double.POSITIVE_INFINITY;Frame reference=goal.center;
            for(boolean assignment:new boolean[]{false,true}) {
                swap=assignment;
                for(Frame ref:goal.alternatives){double s=posture(sample,ref);if(s<best){best=s;reference=ref;chosen=assignment;}}
            }
            swap=chosen;last=time;
            if(best>1){mismatch(time);message=label()+"Ajusta la posición inicial de dedos y manos";return;}
            lost=-1;if(previousSwap!=swap)clearHold();
            message=label()+"Bien, mantén un instante el inicio";
            if(hold(time,sample,tolerance.startHoldMs)){startRoute(sample);began=time;advance(time,sample,reference);}
            return;
        }
        if(time-began>tolerance.maxAttemptMs){reset();last=time;message="Vamos otra vez desde el inicio";return;}
        if(!hasHands(sample,goal.center)) {
            boolean missingEstablished=false;
            for(String s:goal.center.hands.keySet())if(hand(sample,s)==null && referenceAnchor.hands.containsKey(s) && hand(anchor,s)!=null)missingEstablished=true;
            if(missingEstablished)missing(time,"Recupera la mano que estaba participando");
            else {
                last=time;clearHold();
                // No iniciar la cuenta de pérdida para una mano que aún no entró en esta fase.
                message=label()+"Introduce la otra mano cuando lo indique el video";
                if(sample.valid())observeMotion(time,sample);
            }
            return;
        }
        last=time;lost=-1;observeMotion(time,sample);
        observeRoute(sample);
        if(stage==Stage.END && options.pathTolerance>0 &&
            (!routeReached(goal.center) || !routeMatches(sample,goal.center))) {
            clearHold();message=label()+"Completa el último recorrido y llega a la posición final";return;
        }
        if(stage==Stage.MOVE) {
            for(Frame ref:goal.alternatives)if(movement(sample,ref,true)){advance(time,sample,ref);return;}
            message=label()+(motionSamples<options.motionSamples?"Haz un desplazamiento continuo; puedes cambiar los dedos":
                excursion<options.minDisplacement?"Desplaza un poco más la mano":"Lleva la mano hacia la siguiente posición del video");
            return;
        }
        Frame best=null;double score=Double.POSITIVE_INFINITY;
        for(Frame ref:(stage==Stage.END && options.pathTolerance>0?Collections.singletonList(goal.center):goal.alternatives)) {
            double s=posture(sample,ref);
            if(s<score && movement(sample,ref,false)){score=s;best=ref;}
        }
        if(best==null || score>(stage==Stage.END?options.finalPoseScale:1)) {
            mismatch(time);
            message=label()+(best==null?(stage==Stage.END?"Lleva la mano a la zona final del video":"Acerca la mano a la siguiente posición"):
                (stage==Stage.END?"Ajusta la posición final de dedos y manos":"Haz el cambio de dedos y orientación del video"));
            return;
        }
        long required=stage==Stage.END?tolerance.endHoldMs:options.poseHoldMs;
        message=label()+(stage==Stage.END?"Bien, mantén un instante el final":"Bien, cambio de posición reconocido");
        if(hold(time,sample,required) && (stage!=Stage.END || time-began>=tolerance.minAttemptMs))advance(time,sample,best);
    }
}
