'use strict'
const { spawn } = require('node:child_process'), fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict')
const { once } = require('node:events')
const WS = require('../../controller/node-js/maxbot/node_modules/ws')
const { start } = require('../../controller/node-js/maxbot/server'), { sign } = require('../../controller/node-js/maxbot/hub')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function main () {
  // Isolated localhost instance with a synthetic key/robot; never reaches the vehicle.
  const key = 'synthetic-browser-test-key-at-least-32', app = start({ key, port: 0, signalDirectory: path.resolve('.tools/signal-test-sessions') })
  await once(app.server, 'listening'); const port = app.server.address().port
  const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-wasd-'))
  const chrome = spawn(process.env.MAXBOT_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { stdio: 'ignore' })
  let ws, robot, telemetryTimer
  try {
    let debugPort
    for (let n = 0; n < 100; n++) { try { debugPort = Number((await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break } catch { await delay(100) } }
    assert.ok(debugPort)
    const pages = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()
    ws = new WS(pages.find(page => page.type === 'page').webSocketDebuggerUrl); await once(ws, 'open')
    let sequence = 0; const pending = new Map(), errors = []
    ws.on('message', raw => { const m = JSON.parse(raw); if (m.id) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(m.error) : p.resolve(m.result) } else if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails) })
    const send = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })) })
    const evaluate = async expression => { const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails)); return r.result.value }
    await send('Runtime.enable'); await send('Page.enable'); await fs.mkdir('.tools/wasd-review', { recursive: true })
    for (const [name, width, height] of [['wasd-desktop', 1440, 1100], ['wasd-mobile', 390, 900]]) {
      await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
      await send('Page.navigate', { url: `http://127.0.0.1:${port}/` }); await delay(500)
      await evaluate('document.fonts.ready.then(()=>true)')
      const layout = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,holdButton:!!document.getElementById("deadman"),keys:[...document.querySelectorAll(".drive-key button")].map(e=>({key:e.textContent,display:getComputedStyle(e).display})),rates:[document.getElementById("drive-rate").value,document.getElementById("steering-rate").value]})')
      assert.ok(layout.scrollWidth <= layout.width); assert.equal(layout.holdButton, false); assert.equal(layout.keys.length, 4); assert.ok(layout.keys.every(k => k.display !== 'none')); assert.deepEqual(layout.rates, ['100', '250'])
      const metrics = await send('Page.getLayoutMetrics')
      const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width, height: metrics.cssContentSize.height, scale: 1 } })
      await fs.writeFile(`.tools/wasd-review/${name}.png`, Buffer.from(shot.data, 'base64'))
      console.log(name, JSON.stringify(layout))
    }
    await send('Emulation.setFocusEmulationEnabled', { enabled: true }); await send('Page.bringToFront')
    const received = []
    robot = new WS(`ws://127.0.0.1:${port}/robot`)
    const challenge = JSON.parse((await once(robot, 'message'))[0].toString())
    const auth = once(robot, 'message'); robot.send(JSON.stringify({ type: 'auth', role: 'robot', mac: sign(key, `AUTH|robot|${challenge.nonce}`) })); await auth
    let armed = false
    robot.on('message', raw => { const m = JSON.parse(raw); received.push(m); if (m.action === 'arm_manual') armed = true; if (m.action === 'stop') armed = false })
    let tuningSettings={kp_milli:50000,ki_milli:3000,kd_milli:1300,deadband_mdeg:3500,min_pwm:0,max_pwm:255,filter_millihz:12000}, tuningSaved=false
    app.hub.onTuning=async(m,operator)=>{
      if(m.operation!=='get'){assert.equal(armed,false);tuningSettings={...m.settings};tuningSaved=m.operation==='save'}
      app.hub.send(operator,{type:'steering_tuning',request_id:m.request_id,device:{...tuningSettings,status:m.operation==='get'?'ok':m.operation==='save'?'saved':'applied',saved:tuningSaved,armed,diagnostics_version:2,pid_active:armed,feedback_valid:true,feedback_min_mdeg:-60000,feedback_max_mdeg:60000,feedback_rejects:0,command_min_mdeg:-26000,command_max_mdeg:26000,drive_pwm:0,target_mdeg:2000,actual_mdeg:1200,steering_pwm:armed?80:0,p_milli:armed?40000:0,i_milli:0,d_milli:armed?-1200:0,pot_adc:775}})
    }
    const telemetry = () => robot.send(JSON.stringify({ type: 'telemetry', signal_monitor_available: true, mode: 'STOPPED', armed, manual_armed: armed, calibrated: false, manual_ready: true, manual_missing: [], manual_max_pwm: 255, steering_limits_deg: [-26, 26], missing: [], battery_monitoring: false }))
    telemetryTimer = setInterval(telemetry, 50)
    await evaluate(`document.getElementById('key').value=${JSON.stringify(key)};document.getElementById('pair').requestSubmit()`)
    for (let n = 0; n < 50 && !(await evaluate('paired && robot && latest?.manual_ready')); n++) await delay(50)
    await evaluate('document.getElementById("steering-tuning").open=true')
    for(let n=0;n<50 && !(await evaluate('steeringPanel.state'));n++)await delay(30)
    assert.equal(await evaluate('document.getElementById("pid-deadband").value'),'3.5')
    await evaluate('document.getElementById("pid-preset").click();document.getElementById("pid-apply").click()')
    for(let n=0;n<50 && !(await evaluate('steeringPanel.state?.deadband_mdeg===750'));n++)await delay(30)
    assert.equal(await evaluate('steeringPanel.state.deadband_mdeg'),750)
    await evaluate('document.getElementById("pid-save").click()')
    for(let n=0;n<50 && !(await evaluate('steeringPanel.state?.saved'));n++)await delay(30)
    assert.equal(await evaluate('steeringPanel.state.saved'),true)
    assert.match(await evaluate('document.getElementById("pid-status").textContent'),/Saved to ESP32 flash/)
    for(const [name,width,height] of [['pid-desktop',1440,1100],['pid-mobile',390,900]]){
      await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false})
      await evaluate('document.getElementById("steering-tuning").scrollIntoView({block:"start"})');await delay(100)
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true)
      const shot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})
      await fs.writeFile('.tools/wasd-review/'+name+'.png',Buffer.from(shot.data,'base64'))
    }
    await evaluate('document.getElementById("steering-tuning").open=false')
    await evaluate('document.querySelector("[data-action=arm_manual]").click()'); await delay(120)
    assert.equal(await evaluate('document.getElementById("manual-control").dataset.ready'), 'true')
    // Real browser keyboard input, real operator websocket, real Hub forwarding to the isolated robot.
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'w', code: 'KeyW', windowsVirtualKeyCode: 87 })
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'd', code: 'KeyD', windowsVirtualKeyCode: 68 })
    await delay(1150)
    let motion = received.filter(m => m.action === 'manual' && m.deadman)
    assert.ok(motion.some(m => m.pwm === 255 && m.steering_deg === -26), JSON.stringify(motion.slice(-3)))
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'w', code: 'KeyW', windowsVirtualKeyCode: 87 })
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'd', code: 'KeyD', windowsVirtualKeyCode: 68 })
    await delay(500)
    motion = received.filter(m => m.action === 'manual'); const decayed = motion.at(-1)
    assert.equal(decayed.pwm, 0); assert.equal(decayed.steering_deg, 0)
    await evaluate('document.getElementById("drive-rate").focus()'); await delay(70)
    assert.equal(received.filter(m => m.action === 'manual').at(-1).deadman, false)
    assert.equal(await evaluate('Number(document.getElementById("throttle").value)'), 0)
    await evaluate('document.getElementById("key-w").scrollIntoView({block:"center"})')
    const button = await evaluate('(()=>{const r=document.getElementById("key-w").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()')
    await send('Input.dispatchMouseEvent', {type:'mousePressed',button:'left',clickCount:1,...button}); await delay(300)
    assert.ok(received.filter(m=>m.action==='manual'&&m.deadman).at(-1).pwm>40)
    await send('Input.dispatchMouseEvent', {type:'mouseReleased',button:'left',clickCount:1,...button}); await delay(400)
    assert.equal(received.filter(m=>m.action==='manual').at(-1).deadman,false)
    // Real WebRTC sender/receiver on loopback. All peers and the robot are synthetic.
    const rtcErrors = []
    robot.on('message', raw => {
      const m = JSON.parse(raw)
      if (m.type === 'rtc') evaluate(`fakeReceive(${JSON.stringify(m)})`).catch(e => rtcErrors.push(e.message))
    })
    await evaluate(`(() => {
      window.fakePeer = new RTCPeerConnection({iceServers:[]}); window.fakeIce = []; window.fakeSignals = [];
      window.fakeInputs = []; window.fakeAuthenticated = false; window.fakeSilent = false;
      window.fakeReceive = async m => {
        if(m.kind==='answer'){await fakePeer.setRemoteDescription({type:'answer',sdp:m.sdp});for(const c of fakeIce)await fakePeer.addIceCandidate(c);fakeIce=[]}
        else if(m.kind==='candidate'){const c={candidate:m.candidate,sdpMid:m.sdpMid,sdpMLineIndex:m.sdpMLineIndex};if(fakePeer.remoteDescription)await fakePeer.addIceCandidate(c);else fakeIce.push(c)}
      };
      fakePeer.onicecandidate=e=>{if(e.candidate)fakeSignals.push({type:'rtc',kind:'candidate',...e.candidate.toJSON()})};
      window.fakeChannel=fakePeer.createDataChannel('maxbot-controls',{ordered:false,maxRetransmits:0});
      const nonce='a'.repeat(32), respond=m=>{if(!fakeSilent)fakeChannel.send(JSON.stringify(m))};
      fakeChannel.onopen=()=>respond({type:'control_challenge',session_id:sessionId,nonce});
      fakeChannel.onmessage=async e=>{
        const m=JSON.parse(e.data);
        if(m.type==='control_auth' && m.mac===await mac(${JSON.stringify(key)},'RTC|'+sessionId+'|'+nonce)){
          fakeAuthenticated=true;respond({type:'control_ready',session_id:sessionId,nonce});
        }else if(fakeAuthenticated && m.type==='command'){
          fakeInputs.push({...m,received_perf:performance.now()});respond({type:'control_ack',event_id:m.event_id});
        }else if(fakeAuthenticated && m.type==='control_ping')respond({type:'control_pong',id:m.id});
      };
      window.fakeCanvas=document.createElement('canvas');fakeCanvas.width=640;fakeCanvas.height=480;
      const ctx=fakeCanvas.getContext('2d');let frame=0;
      window.fakeDraw=setInterval(()=>{ctx.fillStyle=frame++%2?'#1f6eaf':'#60ac4e';ctx.fillRect(0,0,640,480);ctx.fillStyle='#fff';ctx.font='40px sans-serif';ctx.fillText(String(frame),30,100)},1000/30);
      window.fakeStream=fakeCanvas.captureStream(30);window.fakeSender=fakePeer.addTrack(fakeStream.getVideoTracks()[0],fakeStream);
      const parameters=fakeSender.getParameters();parameters.degradationPreference='maintain-resolution';
      for(const encoding of parameters.encodings){encoding.maxBitrate=3000000;encoding.maxFramerate=30;encoding.scaleResolutionDownBy=1}
      return fakeSender.setParameters(parameters).then(()=>true);
      return true;
    })()`)
    const bridge = setInterval(async () => {
      try { for (const m of await evaluate('fakeSignals.splice(0)')) robot.send(JSON.stringify(m)) } catch (e) { rtcErrors.push(e.message) }
    }, 20)
    try {
      const offer = await evaluate('fakePeer.createOffer().then(async s=>{await fakePeer.setLocalDescription(s);return s.sdp})')
      // Exercise ICE candidates arriving before their offer (Android callbacks can race).
      await delay(120)
      robot.send(JSON.stringify({type:'rtc',kind:'offer',sdp:offer}))
      for(let n=0;n<100 && !(await evaluate('realtime?.healthy()'));n++)await delay(50)
      assert.equal(await evaluate('realtime?.healthy()'),true)
      assert.equal(await evaluate('fakeChannel.ordered'),false);assert.equal(await evaluate('fakeChannel.maxRetransmits'),0)
      const fallbackBefore=received.filter(m=>m.action==='manual'&&m.deadman).length
      await send('Input.dispatchKeyEvent',{type:'keyDown',key:'w',code:'KeyW',windowsVirtualKeyCode:87});await delay(650)
      let direct = await evaluate('fakeInputs.filter(m=>m.deadman)')
      assert.ok(direct.length>=20);assert.ok(direct.every(m=>m.deadline_ms-m.event_ms===300));assert.ok(direct.at(-1).pwm>130)
      assert.ok(received.filter(m=>m.action==='manual'&&m.deadman).length-fallbackBefore<5,'healthy direct input should bypass the relay')
      await evaluate('fakeSilent=true');await delay(300)
      assert.ok(received.filter(m=>m.action==='manual'&&m.deadman).length>fallbackBefore+4,'missing return traffic must trigger relay fallback')
      await evaluate('fakeSilent=false');await delay(650)
      assert.equal(await evaluate('realtime.healthy()'),true)
      await send('Input.dispatchKeyEvent',{type:'keyUp',key:'w',code:'KeyW',windowsVirtualKeyCode:87});await delay(1100)
      assert.equal((await evaluate('fakeInputs.at(-1)')).deadman,false)
      assert.equal(received.filter(m=>m.action==='manual').at(-1).deadman,false,'release is also sent reliably')
      await delay(1000);await evaluate('sampleStream()')
      const metrics = await evaluate('({video:streamStats,input:realtime.stats(),frameWidth:document.getElementById("video").videoWidth,status:document.getElementById("stream-status").textContent})')
      assert.equal(metrics.frameWidth,640);assert.equal(metrics.video.width,640);assert.equal(metrics.video.height,480)
      assert.ok(metrics.video.fps>=25,`Expected near 30 fps at 640x480, got ${metrics.video.fps}`);assert.ok(metrics.input.direct_inputs>=20);assert.equal(rtcErrors.length,0)
      await fs.writeFile('.tools/wasd-review/loopback-latency.json',JSON.stringify(metrics,null,2)+'\n')
      console.log('ISOLATED_LOOPBACK_ONLY',JSON.stringify(metrics))
      // One confirmation render with real synthetic video/measurements at both breakpoints.
      for(const [name,width,height] of [['latency-desktop',1440,1100],['latency-mobile',390,900]]){
        await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});await evaluate('document.getElementById("stream-status").scrollIntoView({block:"center"})');await delay(150)
        assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true)
        const shot=await send('Page.captureScreenshot',{format:'png'});await fs.writeFile('.tools/wasd-review/'+name+'.png',Buffer.from(shot.data,'base64'))
      }
    } finally {
      clearInterval(bridge);await evaluate('clearInterval(fakeDraw);fakeChannel.close();fakePeer.close();fakeStream.getTracks().forEach(t=>t.stop())')
    }
    // Full monitoring user flow with an explicitly simulated phone, using the real button.
    let monitorId='',ordinal=0,batch=0,commandSequence=0;
    const trace=(events,phase='recording')=>robot.send(JSON.stringify({type:'signal_trace',monitor_id:monitorId,batch:++batch,dropped:0,phase,events:events.map(event=>({ordinal:++ordinal,event}))}));
    robot.on('message',raw=>{
      const m=JSON.parse(raw);
      if(m.type==='signal_monitor'&&m.operation==='start'){
        monitorId=m.monitor_id;ordinal=batch=0;
        trace([{type:'monitor_started',metadata:{synthetic:true,device:'browser test fixture'}}]);
        const base=process.hrtime.bigint()-500000000n,stamp=(n,ms)=>(base+BigInt(n*40+ms)*1000000n).toString();
        for(let n=0;n<6;n++)trace([{type:'vision_frame',frame_id:'fixture-'+n,sensor_clock_comparable:true,sensor_timestamp_ns:stamp(n,0),image_available_ns:stamp(n,5),image_acquired_ns:stamp(n,6),pack_start_ns:stamp(n,7),published_ns:stamp(n,8),inference_start_ns:stamp(n,9),prepared_ns:stamp(n,10),inferred_ns:stamp(n,20),decision_ns:stamp(n,21),warning:true},{type:'phone_warning_drawn',frame_id:'fixture-'+n,draw_ns:stamp(n,30),warning:true}]);
        robot.send(JSON.stringify({type:'collision_warning',frame_id:'fixture-5',status:'warning',decision_ns:stamp(5,21),advisory:true}));
      }else if(m.type==='signal_monitor'&&m.operation==='stop'){
        trace([{type:'monitor_stopped'}],'stopped');monitorId='';
      }else if(m.action==='manual'&&monitorId){
        const now=process.hrtime.bigint(),ms=Number(now/1000000n),seq=++commandSequence,ns=offset=>(now+BigInt(offset)*1000000n).toString();
        const packet={...m,phone_received_ns:ns(0),mapped_event_ns:(now-BigInt(Date.now()-m.event_ms)*1000000n).toString(),mapped_input_ns:(now-BigInt(Date.now()-m.input_ms)*1000000n).toString(),host_clock_uncertainty_ms:1};
        trace([{type:'manual_input',packet,source_state:ns(0),output_produced_ns:ns(1),accepted:true},{type:'command_sent',boot:'fixture',session:'fixture',seq,source_state:ns(0),mode:'DRIVE_MANUAL',pwm:m.pwm,phone_send_ns:ns(2),phone_socket_sent_ns:ns(3)},{type:'applied',boot:'fixture',session:'fixture',seq,rx_ms:ms+4,validated_ms:ms+5,applied_ms:ms+6},{type:'output_written',boot:'fixture',session:'fixture',seq,pwm:m.pwm,output_ms:ms+7,mapped_phone_output_ns:ns(7),phone_received_ns:ns(8),clock_uncertainty_ms:1,authenticated:true}]);
      }
    });
    await evaluate('document.getElementById("signal-notes").value="SIMULATED browser verification; no hardware measurements";document.getElementById("signal-start").click()');
    for(let n=0;n<100&&!(await evaluate('signalPanel.state==="recording"'));n++)await delay(30);
    assert.equal(await evaluate('signalPanel.state'),'recording');
    assert.match(await evaluate('document.getElementById("warning-title").textContent'),/Collision warning/);
    await evaluate('document.getElementById("signal-start").focus()');
    await send('Input.dispatchKeyEvent',{type:'keyDown',key:'w',code:'KeyW',windowsVirtualKeyCode:87});await delay(200);
    await send('Input.dispatchKeyEvent',{type:'keyUp',key:'w',code:'KeyW',windowsVirtualKeyCode:87});await delay(350);
    for(const [name,width,height] of [['signals-desktop',1440,1100],['signals-mobile',390,900]]){
      await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});await evaluate('document.getElementById("collision-warning").scrollIntoView({block:"start"})');await delay(100);
      assert.equal(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true);
      const shot=await send('Page.captureScreenshot',{format:'png'});await fs.writeFile('.tools/wasd-review/'+name+'.png',Buffer.from(shot.data,'base64'));
    }
    await evaluate('document.getElementById("signal-stop").click()');
    for(let n=0;n<100&&!(await evaluate('signalPanel.state==="saved"'));n++)await delay(30);
    assert.equal(await evaluate('signalPanel.state'),'saved');assert.equal(app.signals.last.result,'complete');
    const signalReport=JSON.parse(await fs.readFile(app.signals.last.file,'utf8'));
    assert.equal(signalReport.summary.metrics.warning_capture_to_phone_draw.p50,30);
    assert.ok(signalReport.summary.metrics.input_to_output.n>0);
    assert.equal(signalReport.summary.actual_traction_motion.measured,false);
    assert.equal(await evaluate('document.getElementById("signal-download").disabled'),false);
    console.log('SIMULATED_MONITORING_ONLY',JSON.stringify(signalReport.summary.counts));
    await evaluate('window.dispatchEvent(new Event("blur"))'); await delay(70)
    assert.ok(received.some(m => m.action === 'stop'))
    assert.equal(errors.length, 0)
    console.log('Browser → WebSocket → Hub → isolated robot: full PWM/steering, release decay, edit-focus zero and window-blur stop passed. No browser exceptions.')
  } finally {
    clearInterval(telemetryTimer); if (robot) robot.terminate(); if (ws) ws.close(); chrome.kill()
    await new Promise(resolve => app.server.close(resolve)); await delay(300); await fs.rm(profile, { recursive: true, force: true })
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
