package org.maxbot.app;
import android.content.Context;
import android.hardware.*;
import android.os.*;
import org.json.*;
/** Dedicated unbatched ~200 Hz logging. Raw phone axes remain explicitly unaligned until measured. */
final class ImuRecorder implements SensorEventListener,AutoCloseable {
  private final SensorManager manager;private final HandlerThread thread=new HandlerThread("maxbot-imu");private final java.util.function.Supplier<RunRecorder> recordings;private final org.maxbot.core.Calibration calibration;private final java.util.function.BiConsumer<Long,Double> gyro;
  private double[] gravity,omega,alpha;private long previousGyroNs;
  volatile long latestGyroNs,latestAccelNs;volatile double rawYawRate;
  private volatile java.util.function.Consumer<JSONObject> trace=event->{};
  private final long[] lastTrace=new long[16];
  void trace(java.util.function.Consumer<JSONObject> trace){this.trace=trace;}
  ImuRecorder(Context c,java.util.function.Supplier<RunRecorder> recordings,org.maxbot.core.Calibration calibration,java.util.function.BiConsumer<Long,Double> gyro){this.recordings=recordings;this.calibration=calibration;this.gyro=gyro;manager=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);thread.start();Handler handler=new Handler(thread.getLooper());for(int type:new int[]{Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GRAVITY}){Sensor sensor=manager.getDefaultSensor(type);if(sensor!=null)manager.registerListener(this,sensor,5000,0,handler);}}
  public void onSensorChanged(SensorEvent e){
    double[] sensorValues=new double[]{e.values[0],e.values[1],e.values[2]};
    boolean aligned=org.maxbot.core.Calibration.validRotation(calibration.camera.sensorToCarRotation)&&org.maxbot.core.Calibration.vector(calibration.camera.gyroBiasRadS)&&org.maxbot.core.Calibration.vector(calibration.camera.imuLeverArmM);
    double[] carValues=aligned?org.maxbot.core.InertialTransform.rotate(calibration.camera.sensorToCarRotation,sensorValues):null;
    if(aligned&&e.sensor.getType()==Sensor.TYPE_GRAVITY)gravity=carValues;
    double[] axle=null;
    if(aligned&&e.sensor.getType()==Sensor.TYPE_ACCELEROMETER&&gravity!=null&&omega!=null&&alpha!=null)axle=org.maxbot.core.InertialTransform.axleAcceleration(carValues,gravity,omega,alpha,calibration.camera.imuLeverArmM);
    if(e.sensor.getType()==Sensor.TYPE_GYROSCOPE){latestGyroNs=e.timestamp;rawYawRate=e.values[2];double[] r=calibration.camera.sensorToCarRotation,bias=calibration.camera.gyroBiasRadS;if(org.maxbot.core.Calibration.validRotation(r)&&org.maxbot.core.Calibration.vector(bias)){rawYawRate=r[6]*(e.values[0]-bias[0])+r[7]*(e.values[1]-bias[1])+r[8]*(e.values[2]-bias[2]);double[] corrected=new double[]{e.values[0]-bias[0],e.values[1]-bias[1],e.values[2]-bias[2]};double[] next=org.maxbot.core.InertialTransform.rotate(r,corrected);
      double dt=(e.timestamp-previousGyroNs)*1e-9;if(omega!=null&&dt>0&&dt<.05){alpha=new double[3];for(int i=0;i<3;i++)alpha[i]=(next[i]-omega[i])/dt;}else alpha=null;omega=next;previousGyroNs=e.timestamp;
      gyro.accept(e.timestamp,rawYawRate);}}
    if(e.sensor.getType()==Sensor.TYPE_ACCELEROMETER)latestAccelNs=e.timestamp;
    RunRecorder recorder=recordings.get();try{JSONObject event=new JSONObject().put("type","imu").put("clock_domain","android_elapsed_realtime").put("timestamp_ns",Long.toString(e.timestamp)).put("sensor_type",e.sensor.getType()).put("accuracy",e.accuracy).put("axes","phone_device").put("sensor_to_car_validated",aligned).put("car_values",carValues==null?JSONObject.NULL:new JSONArray(carValues)).put("axle_acceleration_mps2",axle==null?JSONObject.NULL:new JSONArray(axle)).put("values",new JSONArray(e.values));
      if(recorder!=null)recorder.event(event);
      int type=e.sensor.getType();if(type<lastTrace.length&&e.timestamp-lastTrace[type]>=(type==Sensor.TYPE_GRAVITY?100_000_000L:20_000_000L)){lastTrace[type]=e.timestamp;trace.accept(event);}
    }catch(JSONException ignored){}
  }
  public void onAccuracyChanged(Sensor s,int accuracy){}
  public void close(){manager.unregisterListener(this);thread.quitSafely();}
}
