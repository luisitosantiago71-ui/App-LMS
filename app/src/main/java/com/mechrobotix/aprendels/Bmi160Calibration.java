package com.mechrobotix.aprendels;

/** Stationary gyro bias only; no orientation, finger angles or sign recognition. */
public final class Bmi160Calibration {
    public boolean running=false, ready=false;
    public String message="Sin calibrar";
    public double bx,by,bz;
    private int count;
    private long first,last=-1;
    private final double[] sum=new double[3], squares=new double[3];
    private double px,py,pz;
    public void reset() { running=false;ready=false;bx=by=bz=0;message="Sin calibrar";clearWindow(); }
    private void clearWindow() { count=0;first=0;last=-1;for(int i=0;i<3;i++){sum[i]=0;squares[i]=0;} }
    public void start() {reset();running=true;message="Mantén el sensor inmóvil durante 3 segundos";}
    public void add(Bmi160Sample s) {
        if(!running) return;
        double g=Math.sqrt(s.gx*s.gx+s.gy*s.gy+s.gz*s.gz);
        double norm=s.accelerationNorm();
        double change=Math.sqrt(Math.pow(s.ax-px,2)+Math.pow(s.ay-py,2)+Math.pow(s.az-pz,2));
        if(norm<.85 || norm>1.15 || g>3 || (last>=0 && change>.035)) {
            clearWindow();message="Movimiento detectado: vuelve a dejarlo quieto";return;
        }
        if(last>=0 && (s.deviceMs<=last || s.deviceMs-last>150)) clearWindow();
        if(count==0)first=s.deviceMs;
        last=s.deviceMs;px=s.ax;py=s.ay;pz=s.az;
        double[] v={s.gx,s.gy,s.gz};
        for(int i=0;i<3;i++){sum[i]+=v[i];squares[i]+=v[i]*v[i];}
        count++;
        message="Calibrando: "+Math.min(100,(s.deviceMs-first)*100/3000)+"%";
        if(s.deviceMs-first>=3000 && count>=100) {
            for(int i=0;i<3;i++) if(squares[i]/count-Math.pow(sum[i]/count,2)>.25) {
                clearWindow();message="Lecturas inestables: repite sin moverlo";return;
            }
            bx=sum[0]/count;by=sum[1]/count;bz=sum[2]/count;
            running=false;ready=true;message="Cero del giroscopio calibrado";
        }
    }
}
