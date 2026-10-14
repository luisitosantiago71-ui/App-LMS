package com.mechrobotix.aprendels;

import java.util.*;

/** Selección automática de hitos; Java puro para probarlo sin teléfono. */
public final class WordReferenceBuilder {
    // VERSION conserva el perfil histórico; el perfil fino tiene versión e identidad independientes.
    public static final int VERSION = 2;
    public static final int SPARSE_VERSION = 1;
    public static final int STEP_MS = 100;
    public static final int MAX_GAP_MS = 700;
    public static final int MAX_VIDEO_MS = 60000;
    private WordReferenceBuilder() {}

    public static final class Result {
        public final List<WordPracticeEngine.Frame> keys;
        public final int startMs, endMs, sampledFrames, largestGapMs;
        public Result(List<WordPracticeEngine.Frame> keys, int count, int gap) {
            this.keys=Collections.unmodifiableList(new ArrayList<>(keys));
            startMs=(int)keys.get(0).timeMs; endMs=(int)keys.get(keys.size()-1).timeMs;
            sampledFrames=count; largestGapMs=gap;
        }
    }

    public static Result build(List<WordPracticeEngine.Frame> frames, long durationMs) {
        return build(frames,durationMs,false);
    }

    /** Perfil opcional para movimientos finos. Conserva la selección histórica por defecto. */
    public static Result build(List<WordPracticeEngine.Frame> frames, long durationMs, boolean recoverSparseMotion) {
        require(durationMs>=1000 && durationMs<=MAX_VIDEO_MS,"Usa un video de 1 a 60 segundos con una sola seña");
        long previous=-1;
        List<WordPracticeEngine.Frame> visible=new ArrayList<>();
        for(WordPracticeEngine.Frame f:frames) {
            require(f.timeMs>previous && f.timeMs<durationMs,"Tiempos del video inválidos");previous=f.timeMs;
            if(f.valid() && f.hands.values().stream().anyMatch(h->h.points[0][1]<850))visible.add(f);
        }
        require(visible.size()>=10,"No se ven suficientes manos. Usa un video con las manos completas y buena luz");
        long start=visible.get(0).timeMs,end=visible.get(visible.size()-1).timeMs;
        List<WordPracticeEngine.Frame> selected=new ArrayList<>();
        for(WordPracticeEngine.Frame f:frames)if(f.timeMs>=start && f.timeMs<=end && f.valid())selected.add(f);
        // Evitar tomar el primer/último cuadro de entrada o retirada de las manos como postura.
        int firstStable=-1,lastStable=-1;
        for(int i=0;i+2<selected.size();i++) {
            FrameWindowCheck:
            if(selected.get(i+2).timeMs-selected.get(i).timeMs>=200 &&
               selected.get(i+2).timeMs-selected.get(i).timeMs<=400) {
                for(int k=i+1;k<=i+2;k++)if(!selected.get(i).hands.keySet().equals(selected.get(k).hands.keySet()) ||
                    WordPracticeEngine.change(selected.get(i),selected.get(k))>.25)break FrameWindowCheck;
                if(firstStable<0)firstStable=i+1;
                lastStable=i+1;
            }
        }
        if(firstStable>=0 && lastStable>firstStable && selected.get(lastStable).timeMs-selected.get(firstStable).timeMs>=900) {
            selected=new ArrayList<>(selected.subList(firstStable,lastStable+1));
            start=selected.get(0).timeMs;end=selected.get(selected.size()-1).timeMs;
        }
        require(end-start>=900,"La seña visible es demasiado corta; deja un momento al inicio y al final");
        int gap=0;
        double[] lengths=new double[selected.size()];
        for(int i=1;i<selected.size();i++) {
            gap=Math.max(gap,(int)(selected.get(i).timeMs-selected.get(i-1).timeMs));
            lengths[i]=lengths[i-1]+Math.min(2,WordPracticeEngine.change(selected.get(i-1),selected.get(i)));
        }
        require(gap<=MAX_GAP_MS,"Se pierden las manos durante demasiado tiempo. Usa un video sin cortes ni oclusiones largas");
        double length=lengths[lengths.length-1];
        require(length>=.6,"No se distingue una secuencia de movimiento. Usa un ejemplo completo y con las manos visibles");
        int count=Math.min(12,Math.max(5,(int)Math.round((end-start)/650.0)+2));
        SortedSet<Integer> indices=new TreeSet<>();indices.add(0);indices.add(selected.size()-1);
        for(int k=1;k<count-1;k++) {
            double target=length*k/(count-1);
            for(int i=1;i<lengths.length;i++)if(lengths[i]>=target){indices.add(i);break;}
        }
        // Preservar las fases sostenidas de dos manos; no inventar una mano oculta.
        int run=-1;
        for(int i=0;i<=selected.size();i++) {
            if(i<selected.size() && selected.get(i).hands.size()==2){if(run<0)run=i;}
            else if(run>=0){if(i-run>=2)indices.add(run+(i-run)/2);run=-1;}
        }
        List<WordPracticeEngine.Frame> keys=new ArrayList<>();
        for(int i:indices) {
            WordPracticeEngine.Frame f=selected.get(i);
            if(!keys.isEmpty()) {
                WordPracticeEngine.Frame last=keys.get(keys.size()-1);
                if(last.hands.keySet().equals(f.hands.keySet()) && WordPracticeEngine.change(last,f)<.13)continue;
            }
            keys.add(f);
        }
        WordPracticeEngine.Frame last=selected.get(selected.size()-1),lastKey=keys.get(keys.size()-1);
        if(lastKey.timeMs!=last.timeMs) {
            if(lastKey.hands.keySet().equals(last.hands.keySet()) && WordPracticeEngine.change(lastKey,last)<.13)keys.set(keys.size()-1,last);
            else keys.add(last);
        }
        if(recoverSparseMotion && keys.size()<4) {
            // Recuperar cuadros reales descartados por el umbral de deduplicación .13.
            // No interpolar, duplicar ni inventar movimiento para alcanzar cuatro hitos.
            while(keys.size()<4) {
                WordPracticeEngine.Frame best=null;double bestDifference=.02;
                for(WordPracticeEngine.Frame candidate:selected) {
                    if(keys.stream().anyMatch(k->k.timeMs==candidate.timeMs))continue;
                    double difference=Double.POSITIVE_INFINITY;
                    for(WordPracticeEngine.Frame key:keys)difference=Math.min(difference,WordPracticeEngine.change(key,candidate));
                    if(difference>bestDifference){bestDifference=difference;best=candidate;}
                }
                if(best==null)break;
                keys.add(best);keys.sort(Comparator.comparingLong(f->f.timeMs));
            }
        }
        require(keys.size()>=4,"No se distinguen al menos cuatro hitos; usa un video de la palabra completa");
        String primary=null;int most=-1;
        for(String side:keys.get(0).hands.keySet()) {
            int n=0;for(WordPracticeEngine.Frame f:selected)if(f.hands.containsKey(side))n++;
            if(n>most){primary=side;most=n;}
        }
        List<WordPracticeEngine.Frame> ordered=new ArrayList<>();
        for(WordPracticeEngine.Frame f:keys) {
            Map<String,JPracticeEngine.Sample> hands=new LinkedHashMap<>();
            if(f.hands.containsKey(primary))hands.put(primary,f.hands.get(primary));
            for(String side:f.hands.keySet())if(!side.equals(primary))hands.put(side,f.hands.get(side));
            ordered.add(new WordPracticeEngine.Frame(f.timeMs,hands));
        }
        return new Result(ordered,selected.size(),gap);
    }
    private static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException(reason);}
}
