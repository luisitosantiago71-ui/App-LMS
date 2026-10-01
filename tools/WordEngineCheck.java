import com.mechrobotix.aprendels.*;
import java.nio.file.*;
import java.util.*;

/** Pruebas de software con detecciones REALES de los diez videos; no sustituye prueba con alumnos. */
public final class WordEngineCheck {
    static class Clip { String name;List<WordPracticeEngine.Frame> keys,frames;WordTolerance tolerance=new WordTolerance(); }
    static WordPracticeEngine.Frame read(Scanner s) {
        long time=s.nextLong();int n=s.nextInt();Map<String,JPracticeEngine.Sample> hands=new LinkedHashMap<>();
        for(int h=0;h<n;h++) {String side=s.next();double[][] points=new double[21][2];double[] flex=new double[10];
            for(int i=0;i<21;i++)for(int j=0;j<2;j++)points[i][j]=s.nextDouble();
            for(int i=0;i<10;i++)flex[i]=s.nextDouble();
            hands.put(side,new JPracticeEngine.Sample(points,flex,side));}
        return new WordPracticeEngine.Frame(time,hands);
    }
    static WordPracticeEngine.Frame camera(WordPracticeEngine.Frame frame,double scale,double dx,double dy,boolean single) {
        Map<String,JPracticeEngine.Sample> hands=new LinkedHashMap<>();
        for(String side:frame.hands.keySet()) {
            if(single && !hands.isEmpty())break;
            JPracticeEngine.Sample a=frame.hands.get(side);double[][] p=new double[21][2];
            for(int i=0;i<21;i++){p[i][0]=dx-a.points[i][0]*scale;p[i][1]=dy+a.points[i][1]*scale;}
            hands.put(side,new JPracticeEngine.Sample(p,a.flex,side));
        }return new WordPracticeEngine.Frame(frame.timeMs,hands);
    }
    static WordPracticeEngine replay(Clip target,List<WordPracticeEngine.Frame> stream,boolean single,double scale) {
        WordPracticeEngine e=new WordPracticeEngine(target.keys,target.tolerance,true);long time=1000;
        WordPracticeEngine.Frame first=camera(stream.get(0),scale,1300,70,single);
        for(int i=0;i<5;i++){e.update(time,first);time+=100;}
        for(WordPracticeEngine.Frame f:stream){e.update(time,camera(f,scale,1300,70,single));time+=100;}
        WordPracticeEngine.Frame last=camera(stream.get(stream.size()-1),scale,1300,70,single);
        for(int i=0;i<6;i++){e.update(time,last);time+=100;}
        return e;
    }
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        List<Clip> clips=new ArrayList<>();
        try(var paths=Files.list(Path.of(args[0]))) {
            for(Path p:paths.filter(f->f.toString().endsWith(".txt")).sorted().toList()){
                Clip c=new Clip();c.name=p.getFileName().toString();c.keys=new ArrayList<>();c.frames=new ArrayList<>();
                try(Scanner s=new Scanner(p).useLocale(Locale.US)){int n=s.nextInt();for(int i=0;i<n;i++)c.keys.add(read(s));n=s.nextInt();for(int i=0;i<n;i++)c.frames.add(read(s));}
                Path props=p.resolveSibling(p.getFileName().toString().replace(".txt",".properties"));
                if(Files.exists(props)){
                    Properties values=new Properties();try(var reader=Files.newBufferedReader(props)){values.load(reader);}
                    for(String field:values.stringPropertyNames()){
                        var f=WordTolerance.class.getField(field);
                        if(f.getType()==long.class)f.setLong(c.tolerance,Long.parseLong(values.getProperty(field)));
                        else f.setDouble(c.tolerance,Double.parseDouble(values.getProperty(field)));
                    }c.tolerance.validate();
                }
                clips.add(c);
            }
        }
        int failures=0,cross=0;
        for(Clip c:clips) {
            WordPracticeEngine exact=replay(c,c.frames,false,1);
            System.out.println(c.name+": "+exact.getStage()+" key="+exact.getTarget()+"/"+(exact.getKeyCount()-1)+" "+exact.getMessage());
            if(exact.getStage()!=WordPracticeEngine.Stage.SUCCESS)failures++;
            WordPracticeEngine scaled=replay(c,c.frames,false,.7);
            if(scaled.getStage()!=WordPracticeEngine.Stage.SUCCESS)failures++;
            List<WordPracticeEngine.Frame> still=Collections.nCopies(120,c.frames.get(0));
            require(replay(c,still,false,1).getStage()!=WordPracticeEngine.Stage.SUCCESS,"Stationary accepted: "+c.name);
            List<WordPracticeEngine.Frame> endpoints=new ArrayList<>();
            endpoints.addAll(Collections.nCopies(8,c.frames.get(0)));
            endpoints.addAll(Collections.nCopies(50,c.frames.get(c.frames.size()-1)));
            require(replay(c,endpoints,false,1).getStage()!=WordPracticeEngine.Stage.SUCCESS,"Skipped path accepted: "+c.name);
            List<WordPracticeEngine.Frame> reverse=new ArrayList<>(c.frames);Collections.reverse(reverse);
            require(replay(c,reverse,false,1).getStage()!=WordPracticeEngine.Stage.SUCCESS,"Reverse accepted: "+c.name);
            if(c.keys.stream().anyMatch(f->f.hands.size()==2))require(replay(c,c.frames,true,1).getStage()!=WordPracticeEngine.Stage.SUCCESS,"Missing second hand accepted: "+c.name);
            for(Clip other:clips)if(c!=other && replay(c,other.frames,false,1).getStage()==WordPracticeEngine.Stage.SUCCESS){cross++;System.out.println("CONFUSION "+c.name+" <- "+other.name);}
        }
        System.out.println("Positive failures="+failures+"; cross-word acceptances="+cross+" / "+clips.size()*(clips.size()-1));
        require(failures==0,"Positive replay failures");require(cross==0,"Cross-word acceptance");
        // Pérdida prolongada, marcas repetidas y pausa no deben otorgar éxito.
        Clip c=clips.get(0);WordPracticeEngine e=new WordPracticeEngine(c.keys,new WordTolerance(),true);
        var first=camera(c.frames.get(0),1,1300,70,false);
        for(int i=0;i<50;i++)e.update(1000,first);
        require(e.getStage()==WordPracticeEngine.Stage.START,"Repeated timestamps counted");
        e.update(1300,first);e.missing(1400,"Sin manos");e.missing(2700,"Sin manos");
        require(e.getStage()==WordPracticeEngine.Stage.START,"Long loss did not reset");
        System.out.println("PASS: replay, scale/translation, stationary, skipped path, reverse, missing hand, cross-word, duplicate time and tracking loss.");
    }
}
