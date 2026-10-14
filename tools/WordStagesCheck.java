import com.mechrobotix.aprendels.*;
import java.nio.file.*;
import java.util.*;

/** Tests del modo por etapas con videos completos; las confusiones se reportan aparte. */
public final class WordStagesCheck {
    static class Clip {
        String name;List<WordPracticeEngine.Frame> frames,keys;
        WordTolerance tolerance=new WordTolerance();WordStageOptions options=new WordStageOptions();
    }
    static void settings(Object target,Path path)throws Exception {
        Properties props=new Properties();try(var reader=Files.newBufferedReader(path)){props.load(reader);}
        for(String k:props.stringPropertyNames()) {
            var f=target.getClass().getField(k);String v=props.getProperty(k);
            if(f.getType()==boolean.class)f.setBoolean(target,Boolean.parseBoolean(v));
            else if(f.getType()==int.class)f.setInt(target,Integer.parseInt(v));
            else if(f.getType()==long.class)f.setLong(target,Long.parseLong(v));else f.setDouble(target,Double.parseDouble(v));
        }
    }
    static WordPracticeEngine.Frame camera(WordPracticeEngine.Frame frame,Map<String,JPracticeEngine.Sample> starts,double shortened,boolean noise,boolean single) {
        Map<String,JPracticeEngine.Sample> hands=new LinkedHashMap<>();
        for(var entry:frame.hands.entrySet()) {
            if(single && !hands.isEmpty())break;
            String side=entry.getKey();var h=entry.getValue();var initial=starts.get(side);if(initial==null)initial=h;
            double[][] p=new double[21][2];double[] flex=h.flex.clone();
            for(int i=0;i<21;i++) {
                double x=initial.points[0][0]+(h.points[0][0]-initial.points[0][0])*shortened+(h.points[i][0]-h.points[0][0]);
                double y=initial.points[0][1]+(h.points[0][1]-initial.points[0][1])*shortened+(h.points[i][1]-h.points[0][1]);
                p[i][0]=1300-.7*x+(noise?Math.sin(frame.timeMs*.01+i)*3:0);
                p[i][1]=70+.7*y+(noise?Math.cos(frame.timeMs*.01+i)*3:0);
            }
            if(noise)for(int i=0;i<flex.length;i++)flex[i]=Math.max(0,Math.min(180,flex[i]+Math.sin(i+frame.timeMs*.008)*8));
            hands.put(side,new JPracticeEngine.Sample(p,flex,side));
        }return new WordPracticeEngine.Frame(frame.timeMs,hands);
    }
    static WordPracticeEngine replay(Clip c,List<WordPracticeEngine.Frame> stream,double shortened,boolean noise,boolean single,int stride) {
        WordPracticeEngine e=new WordPracticeEngine(c.keys,c.tolerance,true,c.options);
        Map<String,JPracticeEngine.Sample> starts=new HashMap<>();
        for(var f:stream)for(var entry:f.hands.entrySet())starts.putIfAbsent(entry.getKey(),entry.getValue());
        long time=1000;
        var first=camera(stream.get(0),starts,shortened,noise,single);
        for(int i=0;i<5;i++){e.update(time,first);time+=100;}
        for(int i=0;i<stream.size();i+=stride){var f=stream.get(i);e.update(time,camera(f,starts,shortened,noise,single));time+=100;}
        var last=camera(stream.get(stream.size()-1),starts,shortened,noise,single);
        for(int i=0;i<12;i++){e.update(time,last);time+=100;}
        return e;
    }
    static boolean success(WordPracticeEngine e){return e.getStage()==WordPracticeEngine.Stage.SUCCESS;}
    public static void main(String[] args)throws Exception {
        List<Clip> clips=new ArrayList<>();int failures=0,confusions=0,reverseAccepted=0;
        try(var paths=Files.list(Path.of(args[0]))) {
            for(Path p:paths.filter(f->f.toString().endsWith(".txt")).sorted().toList()) {
                Clip c=new Clip();c.name=p.getFileName().toString().replace(".txt","");c.frames=new ArrayList<>();
                long duration;
                try(Scanner s=new Scanner(p).useLocale(Locale.US)){duration=s.nextLong();int count=s.nextInt();for(int i=0;i<count;i++)c.frames.add(WordEngineCheck.read(s));}
                settings(c.tolerance,p.resolveSibling(c.name+".properties"));settings(c.options,p.resolveSibling(c.name+".validation.properties"));
                var profile=p.resolveSibling(c.name+".reference.properties");
                var ref=WordReferenceBuilder.build(c.frames,duration,Files.exists(profile) && Boolean.parseBoolean(Files.readString(profile).trim()));c.keys=ref.keys;
                c.frames=c.frames.stream().filter(f->f.timeMs>=ref.startMs && f.timeMs<=ref.endMs && f.valid()).toList();
                clips.add(c);
            }
        }
        for(Clip c:clips) {
            var exact=replay(c,c.frames,1,false,false,1);
            var flexible=replay(c,c.frames,.80,true,false,1);
            var fast=replay(c,c.frames,1,false,false,2);
            System.out.println(c.name+" stages="+c.options.stages+" exact="+exact.getStage()+" flexible="+flexible.getStage()+" fast="+fast.getStage());
            if(!success(exact)){failures++;System.out.println("  exact: "+exact.getMessage());}
            if(!success(flexible)){failures++;System.out.println("  flexible: "+flexible.getMessage());}
            if(!success(fast)){failures++;System.out.println("  fast: "+fast.getMessage());}
            var still=Collections.nCopies(100,c.frames.get(0));
            WordEngineCheck.require(!success(replay(c,still,1,false,false,1)),"Stationary: "+c.name);
            List<WordPracticeEngine.Frame> jump=new ArrayList<>();jump.addAll(Collections.nCopies(6,c.frames.get(0)));jump.addAll(Collections.nCopies(70,c.frames.get(c.frames.size()-1)));
            WordEngineCheck.require(!success(replay(c,jump,1,false,false,1)),"Jump endpoints: "+c.name);
            if(new WordStagePlan(c.keys,c.options.stages).goals.stream().anyMatch(g->g.center.hands.size()==2))
                WordEngineCheck.require(!success(replay(c,c.frames,1,false,true,1)),"Missing required hand: "+c.name);
            List<WordPracticeEngine.Frame> reverse=new ArrayList<>(c.frames);Collections.reverse(reverse);
            if(success(replay(c,reverse,1,false,false,1))){reverseAccepted++;System.out.println("REVERSE_ACCEPTED "+c.name);}
            for(Clip other:clips)if(other!=c && success(replay(c,other.frames,1,false,false,1))){confusions++;System.out.println("CONFUSION "+c.name+" <- "+other.name);}
        }
        WordCompletionCheck.check(clips);
        Clip c=clips.get(0);var e=new WordPracticeEngine(c.keys,c.tolerance,false,c.options);var first=c.keys.get(0);
        for(int i=0;i<100;i++)e.update(1000,first);
        WordEngineCheck.require(e.getStage()==WordPracticeEngine.Stage.START,"Duplicate timestamps");
        for(int i=1;i<6;i++)e.update(1000+i*100,first);
        WordEngineCheck.require(e.getStage()!=WordPracticeEngine.Stage.START,"Start hold failed");
        e.missing(1600,"Sin manos");e.missing(1601+c.tolerance.lostGraceMs,"Sin manos");
        WordEngineCheck.require(e.getStage()==WordPracticeEngine.Stage.START,"Loss reset failed");
        System.out.println("Reversed acceptances="+reverseAccepted+"/"+clips.size());
        System.out.println("Positive failures="+failures+"/"+(clips.size()*3)+"; cross-word="+confusions+"/"+(clips.size()*(clips.size()-1)));
        WordEngineCheck.require(failures==0,"Positive variants failed");
        WordEngineCheck.require(reverseAccepted==0,"Reversed sequence accepted");
        for(Clip item:clips)for(int n=4;n<=item.keys.size();n++)for(int count=3;count<=6;count++) {
            var plan=new WordStagePlan(item.keys.subList(0,n),count);
            WordEngineCheck.require(plan.goals.size()==Math.min(n,count),"Stage count");
            long last=-1;
            for(var goal:plan.goals){WordEngineCheck.require(goal.center.timeMs>last && !goal.alternatives.isEmpty(),"Stage order/empty window");last=goal.center.timeMs;}
        }
        System.out.println("PASS: variants, stationary, skipped movement, required hands, duplicate timestamps, tracking loss.");
    }
}
