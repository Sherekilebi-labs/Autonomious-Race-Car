package org.maxbot.core;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One in-flight value and one replaceable pending value; old work never queues up. */
public final class LatestDispatcher<T> implements AutoCloseable {
  private final Executor executor;private final Consumer<T> consume,release;
  private T pending;private boolean scheduled,closed;private long replaced;
  public LatestDispatcher(Executor executor,Consumer<T> consume,Consumer<T> release){this.executor=executor;this.consume=consume;this.release=release;}
  public synchronized void offer(T value){
    if(closed){release.accept(value);return;}
    if(pending!=null){release.accept(pending);replaced++;}pending=value;
    if(!scheduled){scheduled=true;try{executor.execute(this::drain);}catch(RuntimeException e){scheduled=false;T old=pending;pending=null;if(old!=null)release.accept(old);}}
  }
  private void drain(){
    for(;;){T value;synchronized(this){value=pending;pending=null;if(value==null){scheduled=false;return;}}
      try{consume.accept(value);}catch(RuntimeException ignored){/* A failed frame must not stall subsequent frames. */}finally{release.accept(value);}
    }
  }
  public synchronized long replaced(){return replaced;}
  public synchronized void close(){closed=true;if(pending!=null){release.accept(pending);pending=null;}}
}
