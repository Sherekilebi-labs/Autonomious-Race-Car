package org.maxbot.core;

final class ClockSyncTest {
  static void run() {
    ClockSync clock=new ClockSync();
    CoreTest.check(clock.sample("boot",1000,1010,1011,1003),"tight initial clock sample");
    CoreTest.check(clock.toRemote(2000,2000)==2009,"initial mapped clock offset");
    CoreTest.check(clock.sample("boot",2000,2035,2036,2080),"slower sample remains protocol-valid");
    CoreTest.check(clock.toRemote(2080,2080)==2089,"slow asymmetric RTT cannot replace better bounded offset");
    CoreTest.check(Math.abs(clock.uncertaintyMs(2080)-4.154)<.0001,"retained sample keeps original drift age");
    CoreTest.check(clock.driftPpm()==0,"discarded probe does not alter selected clock drift estimate");
    CoreTest.check(clock.toLocal(2100)==2091,"mapping of existing manual deadline does not get renewed by noisy probe");
    CoreTest.check(!Double.isFinite(clock.uncertaintyMs(6004)),"discarded slow sample cannot extend 5-second clock lifetime");
    CoreTest.check(clock.sample("boot",7000,7040,7041,7080),"expired sample replaced even by a wider valid bound");
    CoreTest.check(clock.uncertaintyMs(7080)>20,"wide replacement still exceeds motion uncertainty limit");
    CoreTest.check(clock.sample("boot",8000,8010,8011,8003),"new tight sample replaces aged wider sample");
    CoreTest.check(clock.uncertaintyMs(8003)==2,"fresh tight bound restored");
    CoreTest.check(clock.sample("new-boot",9000,9400,9401,9080),"boot change invalidates old offset before filtering");
    CoreTest.check(clock.toRemote(9080,9080)==9441,"new boot adopts its own mapping despite wider RTT");
    CoreTest.check(!clock.sample("new-boot",10000,15000,15001,10003),"clock discontinuity remains rejected");
    CoreTest.check(!Double.isFinite(clock.uncertaintyMs(10003)),"clock discontinuity does not retain motion authority");
    ClockSync quick=new ClockSync();quick.sample("same",1000,1010,1011,1003);
    CoreTest.check(!quick.sample("same",1200,5000,5001,1203),"clock step detected even with probes less than 500 ms apart");
    CoreTest.check(!Double.isFinite(quick.uncertaintyMs(1203)),"fast clock step revokes mapping before manual deadlines use it");
  }
}
