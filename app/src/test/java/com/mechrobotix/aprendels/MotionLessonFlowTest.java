package com.mechrobotix.aprendels;
import org.junit.Test;
import static org.junit.Assert.*;
public class MotionLessonFlowTest {
 @Test public void demonstrationAutomaticallyEnablesPractice(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(true);
  assertFalse(flow.complete());assertTrue(flow.demoFinished());assertTrue(flow.isPracticing());
  assertTrue(flow.complete());assertEquals(MotionLessonFlow.Phase.COMPLETE,flow.getPhase());
 }
 @Test public void videoWithoutUsableReferenceCannotApprove(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(false);flow.demoFinished();
  assertFalse(flow.isPracticing());assertFalse(flow.complete());assertEquals(MotionLessonFlow.Phase.ERROR,flow.getPhase());
 }
 @Test public void duplicateCompletionCallbackDoesNotRestartOrApprove(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(true);flow.demoFinished();
  assertFalse(flow.demoFinished());assertTrue(flow.isPracticing());flow.complete();
  assertFalse(flow.demoFinished());assertFalse(flow.complete());
 }
 @Test public void changingLetterDiscardsPreviousDemonstration(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(true);flow.loading();
  assertFalse(flow.demoFinished());assertFalse(flow.complete());
 }
 @Test public void replayRequiresAnotherDemonstrationBeforeValidation(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(true);flow.demoFinished();flow.complete();
  flow.demonstrate(true);assertFalse(flow.complete());assertTrue(flow.demoFinished());assertTrue(flow.isPracticing());
 }
 @Test public void failedVideoNeverEnablesPractice(){
  MotionLessonFlow flow=new MotionLessonFlow();flow.demonstrate(true);flow.fail();
  assertFalse(flow.demoFinished());assertFalse(flow.complete());
 }
}
