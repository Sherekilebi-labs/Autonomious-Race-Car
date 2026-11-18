'use strict'

const http = require('node:http')
const path = require('node:path')
const fs = require('node:fs')
const { WebSocketServer } = require('ws')
const { Hub } = require('./hub')
const { OperatorStop } = require('./services/operator-stop')
const { Planner } = require('./services/planner')
const { SteeringTuning } = require('./services/steering-tuning')
const { SignalMonitoring } = require('./services/signal-monitor')

function start ({ key = process.env.MAXBOT_PAIRING_KEY, host = process.env.MAXBOT_HOST || '127.0.0.1', port = Number(process.env.MAXBOT_PORT || 8081), espHost = process.env.MAXBOT_ESP_HOST, steeringPort = 4245, discover = false, signalDirectory = process.env.MAXBOT_SIGNAL_DIRECTORY || path.resolve(__dirname, '../../../runs/signal-propagation') } = {}) {
  const hub = new Hub(key)
  const signals = new SignalMonitoring({ directory: signalDirectory, sendRobot: m => hub.send(hub.robot, m), publish: m => hub.send(hub.operator, m), snapshot: () => hub.lastTelemetry })
  hub.onMonitor = m => signals.request(m)
  hub.onRobotSignal = m => signals.phone(m)
  hub.onDisconnected = role => {signals.disconnect(role);if(hub.experiments){if(role==='phone')hub.experiments.pixelExportResult({error:'Phone disconnected during export'});hub.experiments.abort(role+'_disconnected')}}
  const steering = new SteeringTuning({key,host:espHost || '192.168.4.1',port:steeringPort,relay:request=>{
    if(hub.lastTelemetry?.vehicle_transport!=='usb')return false;
    hub.send(hub.robot,{type:'vehicle_rpc',request});return true;
  }})
  hub.onVehicleRpc=m=>{if(m.reply)steering.receive(Buffer.from(JSON.stringify(m.reply)))}
  hub.onTuning = async (m,peer) => {
    try { const device=await steering.request(m.operation,m.settings);hub.send(peer,{type:'steering_tuning',request_id:m.request_id,device}) }
    catch(error) { hub.send(peer,{type:'steering_tuning',request_id:m.request_id,error:error.message}) }
  }
  const planner = new Planner({ root: path.resolve(__dirname, '../../..'), publish: m => hub.send(hub.operator,m), snapshot: () => {
    const t=hub.lastTelemetry;return t && {stopped: !t.armed && !['RUN','SCOUT','MANUAL'].includes(t.mode) && Date.now()-t.received_ms<500, mapEpoch:t.map_epoch,calibrationId:t.calibration_id,configId:t.config_id,modelId:t.model_id,vehicleModelId:t.vehicle_model_id}
  } })
  const root=path.resolve(__dirname,'../../..')
  const {Experiments}=require('./services/experiments')
  const experiments=new Experiments({hub,signals,root,directory:process.env.MAXBOT_EXPERIMENT_DIRECTORY||path.join(root,'runs/experiments')})
  const {AutomationJobs}=require('./services/automation-jobs')
  const jobs=new AutomationJobs({root,experiments,snapshot:()=>hub.lastTelemetry})
  hub.onTelemetry=()=>{planner.observe();signals.telemetry(hub.lastTelemetry);jobs.observe()}
  experiments.onSaved=id=>{
    const until=Date.now()+5000
    const analyse=()=>{if(shuttingDown)return;if(jobs.stopped()&&!jobs.child)jobs.start('analyse',{id}).catch(e=>hub.send(hub.operator,{type:'experiment_analysis',error:e.message}));else if(Date.now()<until)setTimeout(analyse,100)}
    setTimeout(analyse,100)
  }
  hub.onExperimentPhone=m=>experiments.phone(m)
  hub.onObserver=m=>experiments.observer.receive(m)
  hub.onObserverConnected=()=>experiments.observerConnected()
  hub.onObserverDisconnected=()=>{experiments.abort('observer_disconnected');experiments.observer.disconnect()}
  hub.onTakeover=action=>{if(!['collection','benchmark','review','export'].includes(action))experiments.abort('manual_takeover_'+action)}
  hub.onExperiment=async(m,peer)=>{try{
    let result
    if(m.operation==='attend')result=experiments.attend(peer,m.options||{})
    else if(m.operation==='prepare')result=experiments.prepare(m.recipe,m.metadata,m.parameters)
    else if(m.operation==='start')result=await experiments.start(m.prepared_id)
    else if(m.operation==='abort'){await experiments.abort();result=experiments.current()}
    else if(m.operation==='status')result=experiments.current()
    else throw new Error('Unknown experiment operation')
    hub.send(peer,result)
  }catch(e){hub.send(peer,{type:'experiment_status',status:'error',error:e.message})}}
  const {automationApi}=require('./services/automation-api')
  const automation=automationApi({key,experiments,signals,hub,planner,jobs,shutdown})
  hub.onPlan=request=>planner.run(request)
  hub.onArtifact=(m,peer)=>{
    const t=hub.lastTelemetry
    if(!t||Date.now()-t.received_ms>500||t.armed||['SCOUT','RUN','MANUAL'].includes(t.mode)||!['begin','chunk','activate'].includes(m.operation))return hub.send(peer,{type:'artifact_result',request_id:m.request_id,status:'failed',message:'Fresh stopped/disarmed phone required'})
    hub.send(hub.robot,m)
  }
  if(espHost)hub.directStop=new OperatorStop({key,host:espHost,onStatus:m=>hub.send(hub.operator,m)})
  const {Exports}=require('./services/exports');const exportDirectory=path.resolve(__dirname,'../../../.tools/operator-exports');const exports=new Exports(exportDirectory)
  hub.onExport=async m=>{try{if(hub.lastTelemetry?.armed||['SCOUT','RUN','MANUAL'].includes(hub.lastTelemetry?.mode))throw new Error('Export paused during motion');if(m.operation==='begin'&&experiments.active?.exportWaiting){exports.directory=path.join(experiments.active.dir,'pixel');experiments.active.exportTransfer=m.id;experiments.active.exportHash=m.sha256}
    const result=await exports.receive(m);if(result.status==='saved'&&experiments.active?.exportTransfer===m.id){experiments.pixelExportResult({file:path.relative(experiments.active.dir,result.path),sha256:experiments.active.exportHash});exports.directory=exportDirectory}
    hub.send(hub.robot,{type:'export_ack',request_id:m.request_id,status:'ok',...result});if(result.status==='saved')hub.send(hub.operator,{type:'export',...result})}catch(e){await exports.close();exports.directory=exportDirectory;experiments.pixelExportResult({error:e.message});hub.send(hub.robot,{type:'export_ack',request_id:m.request_id,status:'failed',message:e.message})}}
  let discoverySocket
  if(discover){const {discovery}=require('./services/discovery');discoverySocket=discovery({key,port})}
  hub.experiments=experiments
  const files = { '/': ['index.html', 'text/html'], '/app.js': ['app.js', 'text/javascript'], '/realtime-inputs.js': ['realtime-inputs.js', 'text/javascript'], '/manual-control.js': ['manual-control.js', 'text/javascript'], '/steering-tuning.js': ['steering-tuning.js', 'text/javascript'], '/signal-monitor.js': ['signal-monitor.js', 'text/javascript'], '/style.css': ['style.css', 'text/css'], '/experiments.js': ['experiments.js','text/javascript'] }
  const server = http.createServer(async (req, res) => {
    if(await automation(req,res))return
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('Referrer-Policy', 'no-referrer')
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws: wss:; media-src 'self' blob:; img-src 'self' blob: data:; font-src 'self'; frame-ancestors 'none'")
    const route = (req.url || '').split('?')[0]
    const reportFile = signals.download(route)
    if (reportFile) {
      res.setHeader('Content-Type', reportFile.endsWith('.json') ? 'application/json' : 'text/markdown; charset=utf-8')
      res.setHeader('Content-Disposition', `attachment; filename="${path.basename(reportFile)}"`)
      return fs.createReadStream(reportFile).on('error', () => { res.writeHead(404); res.end('Report unavailable') }).pipe(res)
    }
    if (route === '/health') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ ok: true, robot: Boolean(hub.robot), operator: Boolean(hub.operator) })) }
    const font = /^\/font\/(Gilroy-(Regular|Bold|Medium)\.ttf)$/.exec(route)
    const entry = files[route]
    if (!entry && !font) { res.writeHead(404); return res.end('Not found') }
    const target = font ? path.join(__dirname, '../client/font/Gilroy', font[1]) : path.join(__dirname, 'public', entry[0])
    res.setHeader('Content-Type', font ? 'font/ttf' : entry[1])
    fs.createReadStream(target).on('error', () => { res.writeHead(404); res.end('Not found') }).pipe(res)
  })
  const websocket = new WebSocketServer({ noServer: true, maxPayload: 65536, perMessageDeflate: false })
  server.on('upgrade', (req, socket, head) => {
    const route = (req.url || '').split('?')[0]
    let originValid = true
    try { if (req.headers.origin) originValid = new URL(req.headers.origin).host === req.headers.host } catch { originValid = false }
    if(route==='/operator'&&!['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress))return socket.destroy()
    if (!originValid || !['/robot', '/operator', '/observer'].includes(route)) return socket.destroy()
    websocket.handleUpgrade(req, socket, head, peer => {
      hub.attach(peer, route.slice(1))
      peer.on('message', message => { try { hub.receive(peer, message.toString()) } catch { peer.close(1008, 'Invalid request') } })
      peer.on('close', () => hub.detach(peer)); peer.on('error', () => hub.detach(peer))
    })
  })
  let shuttingDown=false
  async function shutdown(){
    if(shuttingDown)return;shuttingDown=true;experiments.closed=true;jobs.close()
    await experiments.abort('server_shutdown')
    signals.stop('server_shutdown')
    const until=Date.now()+15000
    while(signals.active&&Date.now()<until)await new Promise(resolve=>setTimeout(resolve,100))
    for(const peer of websocket.clients)peer.close(1001,'Dashboard stopped')
    server.closeAllConnections();server.close()
  }
  const timer = setInterval(() => {hub.tick();experiments.tick();jobs.observe()}, 50)
  server.on('error', () => { steering.close(); clearInterval(timer); websocket.close() })
  server.on('close', () => { experiments.close().catch(()=>{});jobs.close();signals.close(); steering.close(); planner.cancel(); exports.close().catch(()=>{}); if(hub.directStop)hub.directStop.close(); if(discoverySocket)discoverySocket.close(); clearInterval(timer); for (const peer of websocket.clients) peer.terminate(); websocket.close() })
  server.listen(port, host, () => console.log(`MaxBot console: http://${host}:${server.address().port} (trusted LAN only)`))
  return { server, hub, websocket, signals, experiments, jobs, shutdown }
}

if (require.main === module) {
  const app = start({host:process.env.MAXBOT_HOST||'0.0.0.0',espHost:process.env.MAXBOT_ESP_HOST||'192.168.4.1',discover:true})
  const {server}=app
  server.on('error', error => { console.error(`MaxBot server failed: ${error.message}`); process.exitCode = 1 })
  const stop = () => app.shutdown().catch(error=>console.error(error.message))
  process.on('SIGTERM', stop); process.on('SIGINT', stop)
}
module.exports = { start }
