package org.maxbot.app;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.maxbot.core.LatestFramePool;
/** Optional video/recording owns copies; inference and camera never wait for its consumer. */
final class FrameFanout implements AutoCloseable {
  private final LatestFramePool pool=new LatestFramePool(640*480*3/2);
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"maxbot-optional-frames"));
  private final AtomicBoolean scheduled=new AtomicBoolean();private final Consumer<LatestFramePool.Slot> consumer;
  private volatile boolean closed;private long latest;private long dropped;
  FrameFanout(Consumer<LatestFramePool.Slot> consumer){this.consumer=consumer;}
  void offer(LatestFramePool.Slot source){if(closed||source.timestampNs-latest<66_000_000L)return;latest=source.timestampNs;LatestFramePool.Slot copy=pool.acquireForCapture();if(copy==null){dropped++;return;}System.arraycopy(source.yuv,0,copy.yuv,0,copy.yuv.length);copy.timestampNs=source.timestampNs;copy.width=source.width;copy.height=source.height;pool.publish(copy);schedule();}
  private void schedule(){if(closed||!scheduled.compareAndSet(false,true))return;worker.execute(()->{LatestFramePool.Slot frame=pool.takeLatest();try{if(frame!=null&&!closed)consumer.accept(frame);}finally{if(frame!=null)pool.release(frame);scheduled.set(false);if(pool.hasPending()&&!closed)schedule();}});}
  long dropped(){return dropped;}
  public void close(){closed=true;worker.shutdown();}
}
