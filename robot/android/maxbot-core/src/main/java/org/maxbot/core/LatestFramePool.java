package org.maxbot.core;

/**
 * Three preallocated slots. A consumer holds its slot until release; capture never overwrites it.
 */
public final class LatestFramePool {
  public static final class Slot {
    public final byte[] yuv;
    public long timestampNs;
    public int width, height;
    public Object metadata;
    private int references;

    private Slot(int bytes) {
      yuv = new byte[bytes];
    }
  }

  private final Slot[] slots;
  private Slot pending;

  public LatestFramePool(int bytes) {
    slots = new Slot[] {new Slot(bytes), new Slot(bytes), new Slot(bytes)};
  }

  public synchronized Slot acquireForCapture() {
    if (pending != null) {
      release(pending);
      pending = null;
    }
    for (Slot s : slots)
      if (s.references == 0) {
        s.references = 1;
        return s;
      }
    return null;
  }

  public synchronized void publish(Slot slot) {
    if (pending != null) release(pending);
    pending = slot;
    notifyAll();
  }

  public synchronized boolean hasPending() {return pending!=null;}

  public synchronized Slot takeLatest() {
    Slot result = pending;
    pending = null;
    return result;
  }

  public synchronized void retain(Slot slot) {
    if (slot.references <= 0) throw new IllegalStateException("released frame");
    slot.references++;
  }

  public synchronized void release(Slot slot) {
    if (slot.references <= 0) throw new IllegalStateException("double release");
    if (--slot.references == 0) slot.metadata = null;
  }
}
