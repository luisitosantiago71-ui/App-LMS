import com.mechrobotix.aprendels.*;
import java.nio.file.*;
import java.util.*;

/** Replays de cada módulo. Cruces diagnósticos; no certifica corrección lingüística. */
public final class WordModuleCheck {
    public static void main(String[] args)throws Exception {
        List<WordStagesCheck.Clip> clips=new ArrayList<>();int failures=0;
        try(var paths=Files.list(Path.of(args[0]))) {
            for(Path p:paths.filter(f->f.toString().endsWith(".txt")).sorted().toList()) {
                var c=new WordStagesCheck.Clip();c.name=p.getFileName().toString().replace(".txt","");c.frames=new ArrayList<>();
                try {
                    long duration;
                    try(Scanner s=new Scanner(p).useLocale(Locale.US)){duration=s.nextLong();int count=s.nextInt();for(int i=0;i<count;i++)c.frames.add(WordEngineCheck.read(s));}
                    WordStagesCheck.settings(c.tolerance,p.resolveSibling(c.name+".properties"));WordStagesCheck.settings(c.options,p.resolveSibling(c.name+".validation.properties"));
                    var ref=WordReferenceBuilder.build(c.frames,duration,Files.readString(p.resolveSibling(c.name+".reference.properties")).trim().equals("true"));c.keys=ref.keys;
                    c.frames=c.frames.stream().filter(f->f.timeMs>=ref.startMs && f.timeMs<=ref.endMs && f.valid()).toList();
                    for(int count=3;count<=6;count++) {
                        var plan=new WordStagePlan(c.keys,count);long previous=-1;
                        WordEngineCheck.require(plan.goals.size()==Math.min(count,c.keys.size()),"Cantidad de etapas");
                        for(var goal:plan.goals){WordEngineCheck.require(goal.center.timeMs>previous && !goal.alternatives.isEmpty(),"Etapas desordenadas");previous=goal.center.timeMs;}
                    }
                    for(var key:c.keys)WordEngineCheck.require(c.frames.stream().anyMatch(f->f.timeMs==key.timeMs),"Hito sin cuadro real");
                    clips.add(c);
                    System.out.println("REFERENCE\t"+c.name+"\t"+ref.startMs+"\t"+ref.endMs+"\t"+c.keys.size());
                }catch(Exception ex){failures++;System.out.println("ERROR\t"+c.name+"\t"+ex.getMessage());}
            }
        }
        for(var c:clips) {
            var exact=WordStagesCheck.replay(c,c.frames,1,false,false,1);
            var noise=WordStagesCheck.replay(c,c.frames,.8,true,false,1);
            var fast=WordStagesCheck.replay(c,c.frames,1,false,false,2);
            var still=WordStagesCheck.replay(c,Collections.nCopies(80,c.frames.get(0)),1,false,false,1);
            List<WordPracticeEngine.Frame> jump=new ArrayList<>(Collections.nCopies(6,c.frames.get(0)));
            jump.addAll(Collections.nCopies(60,c.frames.get(c.frames.size()-1)));
            var jumped=WordStagesCheck.replay(c,jump,1,false,false,1);
            List<WordPracticeEngine.Frame> reversed=new ArrayList<>(c.frames);Collections.reverse(reversed);
            var reverse=WordStagesCheck.replay(c,reversed,1,false,false,1);
            System.out.println("RESULT\t"+c.name+"\t"+exact.getStage()+"\t"+noise.getStage()+"\t"+fast.getStage()+"\t"+still.getStage()+"\t"+jumped.getStage()+"\t"+reverse.getStage());
            for(var e:List.of(exact,noise,fast))if(!WordStagesCheck.success(e)){failures++;System.out.println("DETAIL\t"+c.name+"\t"+e.getMessage());}
            if(WordStagesCheck.success(still) || WordStagesCheck.success(jumped))failures++;
        }
        int cross=0,total=0;
        for(var target:clips)for(var sample:clips)if(target!=sample) {
            total++;
            if(WordStagesCheck.success(WordStagesCheck.replay(target,sample.frames,1,false,false,1))){cross++;System.out.println("CROSS\t"+target.name+"\t"+sample.name);}
        }
        System.out.println("SUMMARY\t"+clips.size()+"\t"+failures+"\t"+cross+"\t"+total);
        if(failures>0)throw new AssertionError("Hay recorridos completos fallidos o negativos básicos aceptados; consulta DETAIL/ERROR.");
    }
}
