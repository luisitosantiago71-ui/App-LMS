package com.mechrobotix.aprendels;

import java.util.*;

/** Convierte la referencia densa en 3–6 objetivos amplios, sin volver a decodificar el video. */
public final class WordStagePlan {
    public enum Kind { START, MOTION, POSE, END }
    public static final class Goal {
        public final Kind kind;
        public final List<WordPracticeEngine.Frame> alternatives;
        public final WordPracticeEngine.Frame center;
        Goal(Kind kind,List<WordPracticeEngine.Frame> keys,int index,int lo,int hi) {
            this.kind=kind;center=keys.get(index);
            List<WordPracticeEngine.Frame> window=new ArrayList<>();
            // Aceptar una postura vecina, sin permitir que desaparezca una segunda mano requerida.
            for(int i=Math.max(lo,index-1);i<=Math.min(hi,index+1);i++)
                if(keys.get(i).hands.keySet().equals(center.hands.keySet()) &&
                    Math.abs(keys.get(i).timeMs-center.timeMs)<=(kind==Kind.MOTION?500:300))window.add(keys.get(i));
            alternatives=Collections.unmodifiableList(window);
        }
    }
    private static double poseDifference(WordPracticeEngine.Frame a,WordPracticeEngine.Frame b) {
        double difference=0;
        for(String side:a.hands.keySet()) {
            var p=a.hands.get(side);var q=b.hands.get(side);
            if(q==null){difference=Math.max(difference,.75);continue;}
            double points=0,flex=0;
            for(int i=1;i<21;i++)points+=Math.hypot(
                (p.points[i][0]-p.points[0][0])/WordPracticeEngine.palmSize(p)-(q.points[i][0]-q.points[0][0])/WordPracticeEngine.palmSize(q),
                (p.points[i][1]-p.points[0][1])/WordPracticeEngine.palmSize(p)-(q.points[i][1]-q.points[0][1])/WordPracticeEngine.palmSize(q));
            for(int i=0;i<10;i++)flex+=Math.abs(p.flex[i]-q.flex[i]);
            difference=Math.max(difference,points/20+flex/900);
        }return difference;
    }
    public final List<Goal> goals;
    public WordStagePlan(List<WordPracticeEngine.Frame> keys,int stages) {
        if(keys.size()<4 || stages<3 || stages>6)throw new IllegalArgumentException("Referencia o número de etapas inválido");
        stages=Math.min(stages,keys.size());
        int last=keys.size()-1;
        double[] cumulative=new double[keys.size()];
        for(int i=1;i<keys.size();i++)cumulative[i]=cumulative[i-1]+Math.min(3,WordPracticeEngine.change(keys.get(i-1),keys.get(i)));
        int middles=stages-2;
        int[] chosen=new int[middles];
        for(int j=0;j<middles;j++) {
            int lo=1+(last-1)*j/middles,hi=(last-1)*(j+1)/middles;
            int best=lo;double score=Double.POSITIVE_INFINITY;
            for(int i=lo;i<=hi;i++) {
                double distance=Math.abs(cumulative[i]/Math.max(.001,cumulative[last])-(j+1.0)/(middles+1));
                // Favorece un cambio de postura de dos manos en el control intermedio.
                if(j==1 && keys.get(i).hands.size()==2)distance-=.12;
                if(distance<score){score=distance;best=i;}
            }
            chosen[j]=best;
        }
        // Elegir la postura intermedia más distinta de inicio/final, no una fracción temporal fija.
        if(middles>=2) {
            int best=2;double bestScore=-Double.MAX_VALUE;
            int lastPose=last-(middles-1);
            for(int i=2;i<=lastPose;i++) {
                double contrast=Math.min(poseDifference(keys.get(i),keys.get(0)),poseDifference(keys.get(i),keys.get(last)));
                double score=contrast-.15*Math.abs(i/(double)last-.5);
                if(score>bestScore){bestScore=score;best=i;}
            }
            chosen[1]=best;
            chosen[0]=Math.max(1,best/2);
            for(int j=2;j<middles;j++)chosen[j]=best+(last-best)*(j-1)/(middles-1);
        }
        // Si solo aparece la segunda mano a mitad de la palabra, conservar esa fase.
        boolean covered=keys.get(0).hands.size()==2 || keys.get(last).hands.size()==2;
        for(int i:chosen)covered|=keys.get(i).hands.size()==2;
        if(!covered)for(int i=1;i<last;i++)if(keys.get(i).hands.size()==2) {
            int bestSlot=-1,bestDistance=Integer.MAX_VALUE;
            for(int slot=0;slot<middles;slot++) {
                int lo=slot==0?0:chosen[slot-1],hi=slot==middles-1?last:chosen[slot+1];
                if(i>lo && i<hi && Math.abs(chosen[slot]-i)<bestDistance){bestSlot=slot;bestDistance=Math.abs(chosen[slot]-i);}
            }
            if(bestSlot>=0)chosen[bestSlot]=i;
            break;
        }
        List<Goal> out=new ArrayList<>();
        out.add(new Goal(Kind.START,keys,0,0,Math.min(1,chosen[0]-1)));
        for(int j=0;j<middles;j++) {
            int lo=j==0?1:chosen[j-1]+1,hi=j==middles-1?last-1:chosen[j+1]-1;
            out.add(new Goal(j%2==1?Kind.POSE:Kind.MOTION,keys,chosen[j],lo,hi));
        }
        out.add(new Goal(Kind.END,keys,last,Math.max(last-1,chosen[middles-1]+1),last));
        goals=Collections.unmodifiableList(out);
    }
}
