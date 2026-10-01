import com.mechrobotix.aprendels.*;
import java.nio.file.*;
import java.util.*;

/** Reproduce el selector Java de Android con detecciones de videos completos. */
public final class WordAutoReferenceCheck {
    static void rejected(List<WordPracticeEngine.Frame> frames,long duration,String name) {
        try {WordReferenceBuilder.build(frames,duration);throw new AssertionError("Se aceptó "+name);}
        catch(IllegalArgumentException expected){}
    }
    static void encode(StringBuilder out,WordPracticeEngine.Frame f) {
        out.append(f.timeMs).append(' ').append(f.hands.size());
        for(var e:f.hands.entrySet()) {
            out.append(' ').append(e.getKey());
            for(var p:e.getValue().points)for(double n:p)out.append(' ').append(n);
            for(double n:e.getValue().flex)out.append(' ').append(n);
        }out.append('\n');
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]);Files.createDirectories(output);
        List<WordPracticeEngine.Frame> example=null;
        try(var paths=Files.list(Path.of(args[0]))) {
            for(Path p:paths.filter(f->f.toString().endsWith(".txt")).sorted().toList()) {
                List<WordPracticeEngine.Frame> frames=new ArrayList<>();long duration;
                try(Scanner s=new Scanner(p).useLocale(Locale.US)) {
                    duration=s.nextLong();int count=s.nextInt();for(int i=0;i<count;i++)frames.add(WordEngineCheck.read(s));
                }
                var ref=WordReferenceBuilder.build(frames,duration);
                var selected=frames.stream().filter(f->f.timeMs>=ref.startMs && f.timeMs<=ref.endMs && f.valid()).toList();
                StringBuilder out=new StringBuilder();out.append(ref.keys.size()).append('\n');
                for(var f:ref.keys)encode(out,f);
                out.append(selected.size()).append('\n');for(var f:selected)encode(out,f);
                Files.writeString(output.resolve(p.getFileName()),out.toString());
                Path props=p.resolveSibling(p.getFileName().toString().replace(".txt",".properties"));
                if(Files.exists(props))Files.copy(props,output.resolve(props.getFileName()),StandardCopyOption.REPLACE_EXISTING);
                System.out.println(p.getFileName()+": "+ref.keys.size()+" hitos, "+ref.startMs+"–"+ref.endMs+" ms; gap="+ref.largestGapMs);
                example=frames;
            }
        }
        rejected(List.of(),3000,"video sin manos");
        rejected(example,900,"duración corta");rejected(example,61000,"duración larga");
        var hand=example.stream().filter(WordPracticeEngine.Frame::valid).findFirst().orElseThrow();
        List<WordPracticeEngine.Frame> still=new ArrayList<>();
        for(int i=0;i<30;i++)still.add(new WordPracticeEngine.Frame(i*100,hand.hands));
        rejected(still,3000,"mano inmóvil");
        List<WordPracticeEngine.Frame> broken=new ArrayList<>(example);Collections.reverse(broken);rejected(broken,60000,"orden inverso");
        broken=new ArrayList<>();
        for(int i=0;i<30;i++)broken.add(new WordPracticeEngine.Frame(i*100,(i>=10 && i<20)?Map.of():hand.hands));
        rejected(broken,3000,"pérdida larga de manos");
        System.out.println("PASS: controles de calidad del generador.");
        WordEngineCheck.main(new String[]{output.toString()});
    }
}
