import com.mechrobotix.aprendels.WordAutoAdvance;
public final class WordAutoAdvanceCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        var a=new WordAutoAdvance(1200);
        check(!a.take(10000),"Sin éxito no avanza");
        a.completed(10000);a.completed(10900);
        check(!a.take(11199),"Conserva el mensaje de éxito");
        check(a.take(11200),"Avanza al cumplir la espera inicial");
        check(!a.take(11201),"No duplica avance");
        a.completed(12000);check(!a.take(20000),"Resultados repetidos no saltan palabras");
        a.reset();a.completed(21000);a.reset();
        check(!a.take(30000),"Pausa/repetir/cambio cancela avance pendiente");
        a.completed(31000);check(a.take(32200),"Nueva palabra puede avanzar");
        System.out.println("PASS: avance único, espera de éxito, cancelación y nueva palabra");
    }
}
