package com.mechrobotix.aprendels;
import java.util.*;
import org.junit.Test;

/** Behavioral tests with synthetic geometry; device accuracy needs real videos. */
public class SequencePracticeEngineTest {
 static JPracticeEngine.Sample pose(double x,double y,boolean mirrored) {
  double[][] p=new double[21][2];
  for(int i=0;i<21;i++){p[i][0]=(i%4)*12;p[i][1]=-20-i*5;}
  p[0]=new double[]{0,0};p[9]=new double[]{0,-100};
  for(double[] q:p){q[0]+=x;q[1]+=y;if(mirrored)q[0]=-q[0];}
  return new JPracticeEngine.Sample(p,new double[10],"Right");
 }
 static List<JPracticeEngine.Sample> reference(){
  List<JPracticeEngine.Sample> frames=new ArrayList<>();
  for(int i=0;i<=28;i++)frames.add(pose(i*12,Math.sin(i*Math.PI/28)*60,false));return frames;
 }
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void run(SequencePracticeEngine e,boolean mirror){
  long t=0;
  for(int i=0;i<7;i++){e.update(t,pose(0,0,mirror));t+=100;}
  for(int i=0;i<=28;i++)for(int j=0;j<3;j++){e.update(t,pose(i*12,Math.sin(i*Math.PI/28)*60,mirror));t+=100;}
  for(int i=0;i<10;i++){e.update(t,pose(336,0,mirror));t+=100;}
 }

 @Test public void referenceReplayCompletes(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());run(e,false);
  require(e.getStage()==JPracticeEngine.Stage.SUCCESS,"Reference replay failed");
 }
 @Test public void mirroredReferenceCompletes(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());e.setMirrored(true);run(e,true);
  require(e.getStage()==JPracticeEngine.Stage.SUCCESS,"Mirrored replay failed");
 }
 @Test public void stationaryHandDoesNotComplete(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<100;i++)e.update(i*100,pose(0,0,false));
  require(e.getStage()!=JPracticeEngine.Stage.SUCCESS,"Stationary pose passed");
 }
 @Test public void smallJitterDoesNotComplete(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<100;i++)e.update(i*100,pose((i%2)*2,0,false));
  require(e.getStage()!=JPracticeEngine.Stage.SUCCESS,"Jitter passed");
 }
 @Test public void missingHandResetsAttempt(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<7;i++)e.update(i*100,pose(0,0,false));
  e.missing(800,"No hand");
  e.missing(1400,"No hand");
  require(e.getStage()==JPracticeEngine.Stage.START && e.getProgress()==0,"Missing did not reset");
 }
 @Test public void staleFramesResetAttempt(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<7;i++)e.update(i*100,pose(0,0,false));
  e.update(2000,pose(100,0,false));
  require(e.getStage()==JPracticeEngine.Stage.START,"Timestamp gap did not reset");
 }
 @Test(expected=IllegalArgumentException.class) public void referenceWithOnlyNoiseIsRejected(){
  List<JPracticeEngine.Sample> still=new ArrayList<>();
  for(int i=0;i<30;i++)still.add(pose(i%2,0,false));
  new SequencePracticeEngine(still);
 }
 @Test public void jumpingToFinalPoseDoesNotComplete(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<7;i++)e.update(i*100,pose(0,0,false));
  for(int i=7;i<45;i++)e.update(i*100,pose(336,0,false));
  require(e.getStage()!=JPracticeEngine.Stage.SUCCESS,"Skipping the movement passed");
 }

 @Test public void allToleranceLevelsReplayAndRejectStillness(){
  for(JPracticeEngine.Tolerance tolerance:JPracticeEngine.Tolerance.values()){
   SequencePracticeEngine e=new SequencePracticeEngine(reference());e.setTolerance(tolerance);run(e,false);
   require(e.getStage()==JPracticeEngine.Stage.SUCCESS,"Replay failed at "+tolerance);
   e.reset();for(int n=0;n<120;n++)e.update(n*100,pose(0,0,false));
   require(e.getStage()!=JPracticeEngine.Stage.SUCCESS,"Stillness passed at "+tolerance);
  }
 }
 @Test public void shortOcclusionFreezesWithoutApproving(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<7;i++)e.update(i*100,pose(0,0,false));
  JPracticeEngine.Stage stage=e.getStage();int progress=e.getProgress();
  e.missing(700,"No hand");e.missing(800,"No hand");
  require(e.getStage()==stage && e.getProgress()==progress,"Short loss advanced/reset unexpectedly");
  e.update(900,pose(0,0,false));
  require(e.getStage()==stage && e.getProgress()==progress,"Resume approved without motion");
 }
 @Test public void wrongInitialShapeIsRejected(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());e.setTolerance(JPracticeEngine.Tolerance.FLEXIBLE);
  for(int i=0;i<10;i++){JPracticeEngine.Sample s=pose(0,0,false);Arrays.fill(s.flex,170);e.update(i*100,s);}
  require(e.getStage()==JPracticeEngine.Stage.START,"Wrong start passed");
 }
 @Test public void changingHandResets(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());
  for(int i=0;i<7;i++)e.update(i*100,pose(0,0,false));
  JPracticeEngine.Sample s=pose(0,0,false);e.update(700,new JPracticeEngine.Sample(s.points,s.flex,"Left"));
  require(e.getStage()==JPracticeEngine.Stage.START,"Hand switch passed");
 }
 static JPracticeEngine.Sample fingerPose(int frame){
  JPracticeEngine.Sample s=pose(0,0,false);
  s.points[8][0]+=frame*2.5;s.points[8][1]+=frame;
  s.flex[2]=frame*3;s.flex[3]=frame*3;
  return s;
 }
 @Test public void fingerOnlyMotionWithoutWristTravelCanComplete(){
  List<JPracticeEngine.Sample> frames=new ArrayList<>();for(int i=0;i<=28;i++)frames.add(fingerPose(i));
  for(JPracticeEngine.Tolerance tolerance:JPracticeEngine.Tolerance.values()){
   SequencePracticeEngine e=new SequencePracticeEngine(frames);e.setTolerance(tolerance);long t=0;
   for(int i=0;i<8;i++){e.update(t,fingerPose(0));t+=100;}
   for(int i=0;i<=28;i++)for(int j=0;j<4;j++){e.update(t,fingerPose(i));t+=100;}
   for(int i=0;i<10;i++){e.update(t,fingerPose(28));t+=100;}
   require(e.getStage()==JPracticeEngine.Stage.SUCCESS,"Finger-only sequence failed at "+tolerance+": "+e.getMessage());
  }
 }
 @Test public void wrongFinalShapeCannotComplete(){
  SequencePracticeEngine e=new SequencePracticeEngine(reference());long t=0;
  for(int i=0;i<8;i++){e.update(t,pose(0,0,false));t+=100;}
  for(int i=0;i<=28;i++)for(int j=0;j<3;j++){
   JPracticeEngine.Sample s=pose(i*12,Math.sin(i*Math.PI/28)*60,false);
   if(i>=18)Arrays.fill(s.flex,170);
   e.update(t,s);t+=100;
  }
  for(int i=0;i<10;i++){JPracticeEngine.Sample s=pose(336,0,false);Arrays.fill(s.flex,170);e.update(t,s);t+=100;}
  require(e.getStage()!=JPracticeEngine.Stage.SUCCESS,"Wrong final posture passed");
 }
}
