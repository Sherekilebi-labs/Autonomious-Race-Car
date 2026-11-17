package org.maxbot.app;

import android.content.Context;
import android.graphics.Canvas;
import android.widget.TextView;
import java.util.function.LongConsumer;

/** Timestamp the first actual Android draw callback, independently of the inference/UI queue. */
final class CollisionWarningView extends TextView {
  private LongConsumer drawn;
  CollisionWarningView(Context context){super(context);setTextSize(20);setPadding(12,16,12,16);setText("Vision warning starting…");}
  void show(String text,int color,LongConsumer drawn){setText(text);setTextColor(color);this.drawn=drawn;invalidate();}
  @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);LongConsumer callback=drawn;drawn=null;if(callback!=null)callback.accept(android.os.SystemClock.elapsedRealtimeNanos());}
}
