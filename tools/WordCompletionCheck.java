import com.mechrobotix.aprendels.*;
import java.util.*;

/** Regresiones de cierre: cortes reales de las detecciones incluidas, no tiempos impuestos a la app. */
public final class WordCompletionCheck {
    static final Map<String,long[]> CUTS=Map.of(
        "d_nos_vemos_manana",new long[]{2500,3000,3500,3900,4000,4200,4300,4400,4500},
        "d_nos_vemos_pronto",new long[]{4000,4500,5000,5500,6000,6500,7000,7500,8000,8500,9000},
        "d_nos_vemos_proxima",new long[]{4000,4500,5000,5500,6000,6500,7000,7500,8000,8500});
    static WordPracticeEngine stopped(WordStagesCheck.Clip clip,long cut,boolean jump) {
        List<WordPracticeEngine.Frame> stream=new ArrayList<>();
        for(var f:clip.frames)if(f.timeMs<=cut)stream.add(f);
        var stop=jump?clip.frames.get(clip.frames.size()-1):stream.get(stream.size()-1);
        // Cuatro segundos quieto: superar minAttempt/endHold no debe inventar recorrido.
        stream.addAll(Collections.nCopies(40,stop));
        return WordStagesCheck.replay(clip,stream,1,false,false,1);
    }
    static long firstSuccess(WordStagesCheck.Clip clip) {
        var engine=new WordPracticeEngine(clip.keys,clip.tolerance,true,clip.options);
        Map<String,JPracticeEngine.Sample> starts=new HashMap<>();
        for(var f:clip.frames)for(var entry:f.hands.entrySet())starts.putIfAbsent(entry.getKey(),entry.getValue());
        long time=1000;
        var first=WordStagesCheck.camera(clip.frames.get(0),starts,1,false,false);
        for(int i=0;i<5;i++){engine.update(time,first);time+=100;}
        for(var frame:clip.frames) {
            engine.update(time,WordStagesCheck.camera(frame,starts,1,false,false));time+=100;
            if(WordStagesCheck.success(engine))return frame.timeMs;
        }
        return -1; // El replay completo aún puede necesitar sostener el último cuadro.
    }
    static void check(List<WordStagesCheck.Clip> clips) {
        int cases=0,accepted=0;
        for(var clip:clips) {
            long[] cuts=CUTS.get(clip.name);if(cuts==null)continue;
            int passed=0;
            for(long cut:cuts) {
                boolean ok=!WordStagesCheck.success(stopped(clip,cut,false));
                if(!ok){accepted++;System.out.println("EARLY_ACCEPTED "+clip.name+" cut="+cut);}
                else passed++;
                cases++;
            }
            System.out.println("FIRST_SUCCESS "+clip.name+" videoMs="+firstSuccess(clip));
            System.out.println("CLOSURE "+clip.name+" interrupted rejected="+passed+"/"+cuts.length);
            WordEngineCheck.require(!WordStagesCheck.success(stopped(clip,clip.name.endsWith("manana")?4500:clip.name.endsWith("pronto")?8000:6500,true)),"Skipped last movement: "+clip.name);
            // Cambiar la cantidad visible conserva la protección del recorrido.
            int original=clip.options.stages;
            try {
                for(int count=3;count<=6;count++) {
                    clip.options.stages=count;
                    WordEngineCheck.require(WordStagesCheck.success(WordStagesCheck.replay(clip,clip.frames,1,false,false,1)),"Complete with "+count+" stages: "+clip.name);
                    WordEngineCheck.require(!WordStagesCheck.success(stopped(clip,cuts[cuts.length-1],false)),"Incomplete with "+count+" stages: "+clip.name);
                }
            } finally {clip.options.stages=original;}
        }
        System.out.println("Premature acceptances="+accepted+"/"+cases);
        WordEngineCheck.require(accepted==0,"Interrupted signs accepted");
        for(int stages:new int[]{2,7}) {
            var options=new WordStageOptions();options.stages=stages;
            boolean rejected=false;try{options.validate();}catch(IllegalArgumentException ex){rejected=true;}
            WordEngineCheck.require(rejected,"Invalid stage count accepted");
        }
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,2.1}) {
            var options=new WordStageOptions();options.pathTolerance=value;
            boolean rejected=false;try{options.validate();}catch(IllegalArgumentException ex){rejected=true;}
            WordEngineCheck.require(rejected,"Invalid pathTolerance accepted");
        }
    }
}
