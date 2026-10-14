import com.mechrobotix.aprendels.*;
import java.nio.file.*;
import java.util.*;

/** Compara márgenes acotados; no altera archivos ni garantiza generalización a otras personas. */
public final class WordBalanceCheck {
    static List<WordStagesCheck.Clip> load(Path dir)throws Exception {
        List<WordStagesCheck.Clip> clips=new ArrayList<>();
        try(var paths=Files.list(dir)) {
            for(Path p:paths.filter(f->f.toString().endsWith(".txt")).sorted().toList()) {
                var c=new WordStagesCheck.Clip();c.name=p.getFileName().toString().replace(".txt","");c.frames=new ArrayList<>();long duration;
                try(Scanner s=new Scanner(p).useLocale(Locale.US)){duration=s.nextLong();int n=s.nextInt();for(int i=0;i<n;i++)c.frames.add(WordEngineCheck.read(s));}
                WordStagesCheck.settings(c.tolerance,p.resolveSibling(c.name+".properties"));WordStagesCheck.settings(c.options,p.resolveSibling(c.name+".validation.properties"));
                var ref=WordReferenceBuilder.build(c.frames,duration);c.keys=ref.keys;
                c.frames=c.frames.stream().filter(f->f.timeMs>=ref.startMs&&f.timeMs<=ref.endMs&&f.valid()).toList();clips.add(c);
            }
        }return clips;
    }
    public static void main(String[] args)throws Exception {
        var clips=load(Path.of(args[0]));
        for(var c:clips) {
            double shape=c.tolerance.shape,mean=c.tolerance.meanFlex,max=c.tolerance.maxFlex;
            double direction=c.tolerance.directionCosine,progress=c.tolerance.minTravelProgress;
            for(int variant=0;variant<7;variant++) {
                c.tolerance.shape=shape;c.tolerance.meanFlex=mean;c.tolerance.maxFlex=max;c.tolerance.directionCosine=direction;c.tolerance.minTravelProgress=progress;
                if(variant==1)c.tolerance.shape=Math.min(shape,.75);
                if(variant==2){c.tolerance.shape=Math.min(shape,.70);c.tolerance.meanFlex=Math.min(mean,50);c.tolerance.maxFlex=Math.min(max,100);}
                if(variant==3){c.tolerance.shape=Math.min(shape,.65);c.tolerance.meanFlex=Math.min(mean,50);c.tolerance.maxFlex=Math.min(max,100);}
                if(variant==4){c.tolerance.directionCosine=0;c.tolerance.minTravelProgress=.30;}
                if(variant==5){c.tolerance.shape=Math.min(shape,.75);c.tolerance.directionCosine=0;c.tolerance.minTravelProgress=.30;}
                if(variant==6){c.tolerance.shape=Math.min(shape,.70);c.tolerance.meanFlex=Math.min(mean,50);c.tolerance.maxFlex=Math.min(max,100);c.tolerance.directionCosine=0;c.tolerance.minTravelProgress=.30;}
                boolean normal=WordStagesCheck.success(WordStagesCheck.replay(c,c.frames,1,false,false,1));
                boolean flexible=WordStagesCheck.success(WordStagesCheck.replay(c,c.frames,.8,true,false,1));
                boolean fast=WordStagesCheck.success(WordStagesCheck.replay(c,c.frames,1,false,false,2));
                List<String> errors=new ArrayList<>();
                for(var other:clips)if(other!=c && WordStagesCheck.success(WordStagesCheck.replay(c,other.frames,1,false,false,1)))errors.add(other.name);
                System.out.println(c.name+" profile="+variant+" positive="+normal+","+flexible+","+fast+" cross="+errors);
            }
            c.tolerance.shape=shape;c.tolerance.meanFlex=mean;c.tolerance.maxFlex=max;c.tolerance.directionCosine=direction;c.tolerance.minTravelProgress=progress;
        }
    }
}
