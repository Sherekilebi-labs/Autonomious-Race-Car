package org.maxbot.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.maxbot.core.Calibration;
import org.maxbot.core.ManualControl;
import org.maxbot.core.Protocol;
import org.maxbot.core.RunSupervisor;

/** One chosen transport per connection. No reconnect can arm the vehicle. */
final class VehicleLink implements AutoCloseable {
  interface Listener {
    void telemetry(JSONObject message, long nowNs);

    void status(boolean connected, String reason);
  }

  private final Activity context;
  private final Calibration config;
  private final java.util.function.LongSupplier elapsedMs,elapsedNs;
  private volatile long motionNotBeforeNs;
  private long manualStopDueNs;
  private final Listener listener;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean closed, connected;
  private final ScheduledExecutorService tx=Executors.newSingleThreadScheduledExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);r.run();},"maxbot-udp-tx"));
  private final java.util.concurrent.atomic.AtomicReference<RunSupervisor.Output> newest=new java.util.concurrent.atomic.AtomicReference<>();
  private final java.util.concurrent.ArrayBlockingQueue<RunSupervisor.Output> urgent=new java.util.concurrent.ArrayBlockingQueue<>(8);
  private final org.maxbot.core.ClockSync clock=new org.maxbot.core.ClockSync();
  final org.maxbot.core.SteeringTiming steeringTiming=new org.maxbot.core.SteeringTiming();
  final org.maxbot.core.CausalTiming timing=new org.maxbot.core.CausalTiming();
  private long epoch,zeroSequence=-1;private volatile long appliedSequence=-1,appliedEpoch=-1;
  private String motionMode="";
  private String motionGate="Waiting for handshake",lastTxMode="";
  private long lastAckMs=-1;
  private int lastAckPwm;
  private volatile DatagramSocket socket;
  private android.net.Network wifiNetwork;
  private InetAddress address;
  private UsbSerialDevice serial;
  private UsbDeviceConnection usbDevice;
  private final String session = UUID.randomUUID().toString().replace("-", "");
  private volatile String boot = "";
  private String helloNonce = "", serialBuffer = "";
  private long seq, offsetMs, helloSentMs, lastDeviceMs = -1;
  private volatile long lastTelemetryMs;
  private volatile boolean deviceManualArmed;
  private boolean permissionReceiver;
  private final String usbAction = "org.maxbot.app.USB_PERMISSION";

  VehicleLink(Activity activity, Calibration c, Listener listener) {
    this(activity,c,listener,SystemClock::elapsedRealtime,SystemClock::elapsedRealtimeNanos);
  }
  VehicleLink(Activity activity,Calibration c,Listener listener,java.util.function.LongSupplier elapsedMs,java.util.function.LongSupplier elapsedNs) {
    context=activity;config=c;this.listener=listener;this.elapsedMs=elapsedMs;this.elapsedNs=elapsedNs;
  }

  void connect() {
    tx.scheduleWithFixedDelay(this::transmitPending,0,2,TimeUnit.MILLISECONDS);
    worker.execute(
        () -> {
          try {
            if (config.network.pairingKey == null || config.network.pairingKey.length() < 32)
              throw new IllegalStateException("Set private pairing key first");
            if ("wifi".equals(config.network.transport)) {
              address = InetAddress.getByName(config.network.espHost);
              // hello() binds/rebinds on every Wi-Fi network change, including joining after launch.
            } else context.runOnUiThread(this::openUsb);
            worker.scheduleWithFixedDelay(this::hello, 0, 500, TimeUnit.MILLISECONDS);
            worker.scheduleWithFixedDelay(
                () -> {
                  timing.expire(elapsedNs.getAsLong());
                  if (connected && elapsedMs.getAsLong() - lastTelemetryMs > (deviceManualArmed?ManualControl.LINK_MS:150)) {
                    connected = false;
                    listener.status(false, "ESP32 telemetry expired");
                  }
                },
                100,
                50,
                TimeUnit.MILLISECONDS);
          } catch (Exception e) {
            listener.status(false, e.getMessage());
          }
        });
  }

  private synchronized void ensureWifiSocket() throws java.io.IOException {
    android.net.Network current = WifiBinding.network(context);
    if (socket != null && !socket.isClosed() && current != null && current.equals(wifiNetwork)) return;
    connected = false;
    boot = "";
    newest.set(null); urgent.clear();
    clock.invalidate(); timing.reset(); steeringTiming.reset();
    lastDeviceMs = -1; appliedSequence = appliedEpoch = zeroSequence = -1; motionMode = "";manualStopDueNs=0; lastAckMs=-1; motionGate="Waiting for handshake"; lastTxMode="";
    if (socket != null) socket.close();
    socket = null; wifiNetwork = null;
    if (current == null) throw new java.io.IOException("Join MaxBot-Drive Wi-Fi, then stay connected without internet");
    DatagramSocket replacement = new DatagramSocket();
    try {
      current.bindSocket(replacement);
      replacement.setSoTimeout(200);
    } catch (java.io.IOException e) { replacement.close(); throw e; }
    wifiNetwork = current; socket = replacement;
    listener.status(false, "Connecting to ESP32; motion remains stopped");
    new Thread(() -> udpRead(replacement), "maxbot-udp-rx").start();
  }

  private void udpRead(DatagramSocket source) {
    byte[] bytes = new byte[2048];
    while (!closed && source == socket && !source.isClosed()) {
      try {
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        source.receive(packet);
        if (source != socket) return;
        if (!packet.getAddress().equals(address) || packet.getPort() != config.network.espPort)
          continue;
        receive(new String(bytes, packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
      } catch (java.net.SocketTimeoutException ignored) {
      } catch (Exception e) {
        if (!closed && source == socket && !source.isClosed()) {
          android.util.Log.w("MaxBotVehicle", "UDP receive failed", e);
          listener.status(false, "UDP receive failed: " + e.getClass().getSimpleName());
          connected = false;
          source.close();
        }
      }
    }
  }

  private final BroadcastReceiver usbReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
          if (usbAction.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                && device != null) startSerial(device);
            else listener.status(false, "USB permission denied");
          } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
            connected = false;
            listener.status(false, "USB detached; reconnect while stopped");
          }
        }
      };

  private void openUsb() {
    UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    IntentFilter filter = new IntentFilter(usbAction);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    ContextCompat.registerReceiver(
        context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    permissionReceiver = true;
    for (UsbDevice device : manager.getDeviceList().values()) {
      if (manager.hasPermission(device)) startSerial(device);
      else
        manager.requestPermission(
            device,
            PendingIntent.getBroadcast(
                context,
                0,
                new Intent(usbAction).setPackage(context.getPackageName()),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      return;
    }
    listener.status(false, "No USB serial device attached");
  }

  private synchronized void startSerial(UsbDevice device) {
    UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    usbDevice = manager.openDevice(device);
    if (usbDevice == null) {
      listener.status(false, "USB open failed");
      return;
    }
    serial = UsbSerialDevice.createUsbSerialDevice(device, usbDevice);
    if (serial == null || !serial.open()) {
      listener.status(false, "Unsupported USB serial chip");
      return;
    }
    serial.setBaudRate(config.network.baud);
    serial.setDataBits(UsbSerialInterface.DATA_BITS_8);
    serial.setStopBits(UsbSerialInterface.STOP_BITS_1);
    serial.setParity(UsbSerialInterface.PARITY_NONE);
    serial.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
    serial.read(
        bytes -> {
          synchronized (this) {
            serialBuffer += new String(bytes, StandardCharsets.UTF_8);
            int newline;
            while ((newline = serialBuffer.indexOf('\n')) >= 0) {
              String line = serialBuffer.substring(0, newline);
              serialBuffer = serialBuffer.substring(newline + 1);
              receive(line);
            }
            if (serialBuffer.length() > 4096) serialBuffer = "";
          }
        });
    hello();
  }

  private synchronized void hello() {
    if (closed) return;
    try {
      if ("wifi".equals(config.network.transport)) ensureWifiSocket();
      helloNonce = UUID.randomUUID().toString().replace("-", "");
      helloSentMs = elapsedMs.getAsLong();
      sendRaw(
          new JSONObject()
              .put("v", 1)
              .put("type", "hello")
              .put("nonce", helloNonce)
              .put("sender_ms",helloSentMs)
              .put("mac", Protocol.sign(config.network.pairingKey, "H|" + helloNonce))
              .toString());
    } catch (Exception e) {
      connected = false;
      if (socket != null) socket.close(); // Retry with a fresh Wi-Fi binding on the next hello.
      android.util.Log.w("MaxBotVehicle", "Handshake send failed", e);
      listener.status(false, "Handshake send failed: " + e.getClass().getSimpleName() + " · " + e.getMessage());
    }
  }

  private synchronized void receive(String line) {
    if (closed || line.length() > 2048) return;
    try {
      JSONObject m = new JSONObject(line);
      if (m.optInt("v") != 1) return;
      long now = elapsedMs.getAsLong();
      if ("hello".equals(m.optString("type"))) {
        long deviceTime = m.getLong("time_ms");
        if (!helloNonce.equals(m.getString("nonce")) || now - helloSentMs > 100||m.getLong("sender_ms")!=helloSentMs) return;
        String newBoot = m.getString("boot");
        String calibrationId = m.getString("calibration_id");
        if (!Protocol.verify(
            config.network.pairingKey,
            "H|" + newBoot + "|" + deviceTime + "|" + helloNonce + "|" + calibrationId+"|"+m.getLong("rx_ms")+"|"+m.getLong("tx_ms")+"|"+helloSentMs+"|"+m.getLong("owner_epoch")+"|"+m.getLong("owner_seq")+"|"+m.getString("firmware_source_id"),
            m.getString("mac"))) return;
        if (!calibrationId.equals(config.firmwareCalibrationId)) {
          boot = "";
          connected = false;
          listener.status(
              false, "Calibration mismatch: regenerate and reflash steering/power configuration");
          return;
        }
        if (!boot.isEmpty() && !boot.equals(newBoot)) {
          connected = false;
          listener.status(false, "ESP32 rebooted; reset and rearm");
          lastDeviceMs = -1;lastAckMs=-1;newest.set(null);urgent.clear();timing.reset();steeringTiming.reset();clock.invalidate();appliedSequence=-1;zeroSequence=-1;epoch++;
        }
        if(boot.isEmpty()||!boot.equals(newBoot)){
          epoch=Math.max(0,m.getLong("owner_epoch")+1);seq=Math.max(0,m.getLong("owner_seq"));
          if(m.getLong("owner_seq")>=0){motionGate="Reset stop required to claim this phone session";listener.status(false,"ESP reachable; press Reset stop to claim this phone session");}
        }
        boot = newBoot;
        boolean accepted=clock.sample(boot,helloSentMs,m.getLong("rx_ms"),m.getLong("tx_ms"),now);
        listener.telemetry(new JSONObject().put("type","clock_sync").put("boot",boot).put("firmware_source_id",m.getString("firmware_source_id")).put("t1_phone_ms",helloSentMs).put("t2_esp_ms",m.getLong("rx_ms")).put("t3_esp_ms",m.getLong("tx_ms")).put("t4_phone_ms",now).put("accepted",accepted).put("uncertainty_ms",Double.isFinite(clock.uncertaintyMs(now))?clock.uncertaintyMs(now):JSONObject.NULL).put("drift_estimate_ppm",clock.driftPpm()),elapsedNs.getAsLong());
        offsetMs=clock.toRemote(now,now)-now;
        // Establish reply destination and session without granting motion authority.
        if (!connected) send(new RunSupervisor.Output("STOP", 0, 0));
        return;
      }
      if(("applied".equals(m.optString("type"))||"local_applied".equals(m.optString("type")))&&boot.equals(m.optString("boot"))&&session.equals(m.optString("session"))){
        String canonical="A|"+boot+"|"+session+"|"+m.getLong("seq")+"|"+m.getLong("epoch")+"|"+m.getLong("rx_ms")+"|"+m.getLong("validated_ms")+"|"+m.getLong("applied_ms")+"|"+m.getLong("expires_ms")+"|"+m.getInt("pwm");
        if("local_applied".equals(m.optString("type")))canonical+="|"+m.getString("reason");
        if(!Protocol.verify(config.network.pairingKey,canonical,m.getString("mac")))return;
        if(m.getLong("seq")>=appliedSequence){lastAckMs=now;lastAckPwm=m.getInt("pwm");}
        if(m.getLong("seq")>appliedSequence){appliedSequence=m.getLong("seq");appliedEpoch=m.getLong("epoch");}
        if(!"local_applied".equals(m.optString("type")))steeringTiming.applied(m.getLong("seq"),m.getLong("applied_ms"));
        if(!"local_applied".equals(m.optString("type")))timing.applied(m.getLong("seq"),clock.toLocal(m.getLong("applied_ms"))*1_000_000L,clock.toLocal(m.getLong("expires_ms"))*1_000_000L,clock.uncertaintyMs(now)<20);
        double bound=clock.uncertaintyMs(now);m.put("phone_received_ns",Long.toString(elapsedNs.getAsLong())).put("mapped_phone_applied_ns",Double.isFinite(bound)?Long.toString(clock.toLocal(m.getLong("applied_ms"))*1_000_000L):JSONObject.NULL).put("clock_uncertainty_ms",Double.isFinite(bound)?bound:JSONObject.NULL);
        listener.telemetry(m,elapsedNs.getAsLong());return;
      }
      if (!"telemetry".equals(m.optString("type"))
          || !boot.equals(m.optString("boot"))
          || !session.equals(m.optString("session"))) return;
      long deviceTime = m.getLong("time_ms");
      if (deviceTime <= lastDeviceMs || Math.abs((now + offsetMs) - deviceTime) > (m.optBoolean("manual_armed")?ManualControl.INPUT_MS:100)) return;
      String fault = m.getString("fault");
      String canonical =
          "T|"
              + boot
              + "|"
              + session
              + "|"
              + deviceTime
              + "|"
              + m.getLong("seq")
              + "|"
              + (m.getBoolean("armed") ? 1 : 0)
              + "|"
              + (m.getBoolean("estop") ? 1 : 0)
              + "|"
              + m.getInt("steering_mdeg")
              + "|"
              + m.getInt("battery_mv")
              + "|"
              + m.getInt("pot_adc")
              + "|"
              + m.getInt("pwm")
              + "|"
              + fault
              + "|"
              + (m.getBoolean("manual_armed") ? 1 : 0);
      canonical+="|"+m.getLong("epoch")+"|"+m.getLong("expiry_ms")+"|"+m.getLong("application_loss")+"|"+m.getLong("motor_overruns")+"|"+m.getLong("deadline_rejected")+"|"+m.getLong("sensor_ms");
      if (!Protocol.verify(config.network.pairingKey, canonical, m.getString("mac"))) return;
      steeringTiming.sample(m.getLong("sensor_ms"),m.getInt("steering_mdeg")/1000.);
      deviceManualArmed=m.getBoolean("manual_armed");
      lastDeviceMs = deviceTime;
      lastTelemetryMs = now;
      if (!connected) {
        connected = true;
        listener.status(true, "Authenticated vehicle link");
      }
      listener.telemetry(m, elapsedNs.getAsLong());
    } catch (Exception ignored) {
      /* Malformed/unsigned/debug lines never refresh freshness. */
    }
  }

  synchronized void transmitPending() {
    if(closed)return;
    RunSupervisor.Output out=urgent.poll();if(out==null)out=newest.getAndSet(null);
    if(out!=null)sendNow(out);
    else if(manualStopDueNs>0&&elapsedNs.getAsLong()>=manualStopDueNs){
      sendNow(new RunSupervisor.Output("STOP",0,0));motionGate="Manual lease nearing expiry; zero output sent";
    }
  }
  void send(RunSupervisor.Output out) {
    if(closed||((out.mode.equals("DRIVE")||out.mode.equals("DRIVE_MANUAL"))&&out.sourceStateNs<=motionNotBeforeNs))return;
    newest.set(out);
  }
  synchronized JSONObject controlStatus() {
    JSONObject status=new JSONObject();
    try {status.put("gate",motionGate).put("last_sent_mode",lastTxMode)
      .put("ack_sequence",appliedSequence).put("ack_epoch",appliedEpoch).put("pending_zero_sequence",zeroSequence)
      .put("ack_age_ms",lastAckMs<0?JSONObject.NULL:Math.max(0,elapsedMs.getAsLong()-lastAckMs))
      .put("ack_pwm",lastAckMs<0?JSONObject.NULL:lastAckPwm);
    }catch(Exception ignored){}
    return status;
  }
  double clockUncertaintyMs(){return clock.uncertaintyMs(elapsedMs.getAsLong());}
  private synchronized void sendNow(RunSupervisor.Output requested) {
    RunSupervisor.Output out=requested;
    if (closed || boot.isEmpty()) return;
    try {
      long now=elapsedMs.getAsLong(),nowNs=elapsedNs.getAsLong();
      boolean motion=out.mode.equals("DRIVE")||out.mode.equals("DRIVE_MANUAL");
      boolean directManual=out.mode.equals("DRIVE_MANUAL");
      int clockLimit=directManual?ManualControl.CLOCK_BOUND_MS:20;
      if(!motion){motionMode="";zeroSequence=-1;}
      if(motion&&out.sourceStateNs<=motionNotBeforeNs){out=new RunSupervisor.Output("STOP",0,0);motion=false;motionMode="";zeroSequence=-1;}
      motionGate=motion?"Motion permitted":out.mode;
      if(motion&&(out.validUntilNs<=nowNs||clock.uncertaintyMs(now)>clockLimit)){
        motionGate=out.validUntilNs<=nowNs?"Manual/control intent expired":"ESP clock uncertainty exceeds "+clockLimit+" ms";
        out=new RunSupervisor.Output("STOP",0,0);
      }
      else if(motion&&(out.validUntilNs-nowNs)/1_000_000L<=(long)Math.ceil(clock.uncertaintyMs(now))){
        motionGate="Remaining command budget is too short";out=new RunSupervisor.Output("STOP",0,0);
      }
      else if(motion&&!directManual&&!motionMode.equals(out.mode)){
        motionGate="Waiting for applied zero acknowledgement";
        if(zeroSequence<0){epoch++;motionMode=out.mode;zeroSequence=seq+1;}
        out=new RunSupervisor.Output("STOP",0,0);
      }else if(motion&&!directManual&&zeroSequence>=0){
        if(appliedEpoch!=epoch||appliedSequence<zeroSequence){motionGate="Waiting for applied zero acknowledgement";out=new RunSupervisor.Output("STOP",0,0);}else zeroSequence=-1;
      }
      if(out.mode.equals("DRIVE_MANUAL")){motionMode="DRIVE_MANUAL";zeroSequence=-1;}
      int leaseLimit=out.mode.equals("DRIVE")?100:ManualControl.INPUT_MS;
      int leaseClockLimit=out.mode.equals("DRIVE")?20:ManualControl.CLOCK_BOUND_MS;
      long issued=clock.toRemote(now,now),sequence=++seq;
      long remaining=out.validUntilNs==Long.MAX_VALUE?leaseLimit:Math.min(leaseLimit,(out.validUntilNs-nowNs)/1_000_000L);
      long expires=issued+Math.max(1,remaining-(long)Math.ceil(Math.min(leaseClockLimit,clock.uncertaintyMs(now))));
      int angle = (int) Math.round(out.steeringDeg * 1000);
      String canonical =
          Protocol.motion(boot,session,epoch,sequence,issued,expires,out.mode,angle,out.pwm,Long.toString(out.sourceStateNs),Long.toString(out.sourceFrameNs),config.firmwareCalibrationId);
      JSONObject m =
          new JSONObject()
              .put("v", 2)
              .put("type", "command")
              .put("boot", boot)
              .put("session", session)
              .put("seq", sequence)
              .put("issued_ms", issued)
              .put("expires_ms",expires)
              .put("epoch",epoch)
              .put("source_state",Long.toString(out.sourceStateNs))
              .put("source_frame",Long.toString(out.sourceFrameNs))
              .put("calibration_id",config.firmwareCalibrationId)
              .put("mode", out.mode)
              .put("steering_mdeg", angle)
              .put("pwm", out.pwm)
              .put("mac", Protocol.sign(config.network.pairingKey, canonical));
      if(out.sourceStateNs>0)timing.sent(sequence,out.sourceFrameNs,out.sourceStateNs,out.producedNs>0?out.producedNs:nowNs,nowNs);
      steeringTiming.sent(sequence,out.steeringDeg,out.mode.equals("DRIVE")||out.mode.equals("DRIVE_MANUAL"));
      sendRaw(m.toString());lastTxMode=out.mode;
      if(out.mode.equals("DRIVE_MANUAL")){
        // Send zero before the last manual lease runs out if the control producer stalls.
        // This never renews motion; the ESP still enforces expiry if that STOP is lost.
        long leadMs=Math.max(15,2*(long)Math.ceil(clock.uncertaintyMs(now))+5);
        manualStopDueNs=Math.min(out.validUntilNs,clock.toLocal(expires)*1_000_000L)-leadMs*1_000_000L;
      }else manualStopDueNs=0;
      JSONObject trace=m;trace.remove("mac");trace.put("type","command_sent").put("phone_send_ns",Long.toString(nowNs)).put("policy_output_ns",Long.toString(out.producedNs)).put("clock_uncertainty_ms",Double.isFinite(clock.uncertaintyMs(now))?clock.uncertaintyMs(now):JSONObject.NULL);
      listener.telemetry(trace,nowNs);
    } catch (Exception e) {
      connected = false;
      listener.status(false, "Vehicle send failed");
    }
  }

  private void sendRaw(String message) throws Exception {
    byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
    if (socket != null)
      socket.send(new DatagramPacket(bytes, bytes.length, address, config.network.espPort));
    else if (serial != null) serial.write(bytes);
    else throw new java.io.IOException("No vehicle transport is open");
  }

  synchronized void special(String mode) {
    if(closed)return;
    newest.set(null);manualStopDueNs=0;motionNotBeforeNs=elapsedNs.getAsLong();motionMode="";zeroSequence=-1;
    if(java.util.List.of("DISARM","ESTOP","RESET").contains(mode))urgent.clear();
    if(mode.equals("RESET"))urgent.offer(new RunSupervisor.Output("DISARM",0,0));
    if(!urgent.offer(new RunSupervisor.Output(mode,0,0))){urgent.clear();urgent.offer(new RunSupervisor.Output("ESTOP",0,0));}
  }

  boolean connected() {
    return connected;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    sendNow(new RunSupervisor.Output("DISARM",0,0));
    closed = true;
    connected = false;
    worker.shutdownNow();tx.shutdownNow();
    if (socket != null) socket.close();
    if (serial != null) serial.close();
    if (usbDevice != null) usbDevice.close();
    if (permissionReceiver) {
      context.unregisterReceiver(usbReceiver);
      permissionReceiver = false;
    }
  }
}
