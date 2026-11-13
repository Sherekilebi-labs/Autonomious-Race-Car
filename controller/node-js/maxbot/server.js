'use strict'

const http = require('node:http')
const path = require('node:path')
const fs = require('node:fs')
const { WebSocketServer } = require('ws')
const { Hub } = require('./hub')
const { OperatorStop } = require('./services/operator-stop')
const { Planner } = require('./services/planner')

function start ({ key = process.env.MAXBOT_PAIRING_KEY, host = process.env.MAXBOT_HOST || '127.0.0.1', port = Number(process.env.MAXBOT_PORT || 8081), espHost = process.env.MAXBOT_ESP_HOST, discover = false } = {}) {
  const hub = new Hub(key)
  const planner = new Planner({ root: path.resolve(__dirname, '../../..'), publish: m => hub.send(hub.operator,m), snapshot: () => {
    const t=hub.lastTelemetry;return t && {stopped: !t.armed && !['RUN','SCOUT','MANUAL'].includes(t.mode) && Date.now()-t.received_ms<500, mapEpoch:t.map_epoch,calibrationId:t.calibration_id,configId:t.config_id,modelId:t.model_id,vehicleModelId:t.vehicle_model_id}
  } })
  hub.onTelemetry=()=>planner.observe()
  hub.onPlan=request=>planner.run(request)
  hub.onArtifact=(m,peer)=>{
    const t=hub.lastTelemetry
    if(!t||Date.now()-t.received_ms>500||t.armed||['SCOUT','RUN','MANUAL'].includes(t.mode)||!['begin','chunk','activate'].includes(m.operation))return hub.send(peer,{type:'artifact_result',request_id:m.request_id,status:'failed',message:'Fresh stopped/disarmed phone required'})
    hub.send(hub.robot,m)
  }
  if(espHost)hub.directStop=new OperatorStop({key,host:espHost,onStatus:m=>hub.send(hub.operator,m)})
  const {Exports}=require('./services/exports');const exports=new Exports(path.resolve(__dirname,'../../../.tools/operator-exports'))
  hub.onExport=async m=>{try{if(hub.lastTelemetry?.armed||['SCOUT','RUN','MANUAL'].includes(hub.lastTelemetry?.mode))throw new Error('Export paused during motion');const result=await exports.receive(m);hub.send(hub.robot,{type:'export_ack',request_id:m.request_id,status:'ok',...result});if(result.status==='saved')hub.send(hub.operator,{type:'export',...result})}catch(e){await exports.close();hub.send(hub.robot,{type:'export_ack',request_id:m.request_id,status:'failed',message:e.message})}}
  let discoverySocket
  if(discover){const {discovery}=require('./services/discovery');discoverySocket=discovery({key,port})}
  const files = { '/': ['index.html', 'text/html'], '/app.js': ['app.js', 'text/javascript'], '/manual-control.js': ['manual-control.js', 'text/javascript'], '/style.css': ['style.css', 'text/css'] }
  const server = http.createServer((req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('Referrer-Policy', 'no-referrer')
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws: wss:; media-src 'self' blob:; img-src 'self' blob: data:; font-src 'self'; frame-ancestors 'none'")
    const route = (req.url || '').split('?')[0]
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
    if (!originValid || !['/robot', '/operator'].includes(route)) return socket.destroy()
    websocket.handleUpgrade(req, socket, head, peer => {
      hub.attach(peer, route === '/robot' ? 'robot' : 'operator')
      peer.on('message', message => { try { hub.receive(peer, message.toString()) } catch { peer.close(1008, 'Invalid request') } })
      peer.on('close', () => hub.detach(peer)); peer.on('error', () => hub.detach(peer))
    })
  })
  const timer = setInterval(() => hub.tick(), 50)
  server.on('error', () => { clearInterval(timer); websocket.close() })
  server.on('close', () => { planner.cancel(); exports.close().catch(()=>{}); if(hub.directStop)hub.directStop.close(); if(discoverySocket)discoverySocket.close(); clearInterval(timer); for (const peer of websocket.clients) peer.terminate(); websocket.close() })
  server.listen(port, host, () => console.log(`MaxBot console: http://${host}:${server.address().port} (trusted LAN only)`))
  return { server, hub, websocket }
}

if (require.main === module) {
  const { server, websocket } = start({host:process.env.MAXBOT_HOST||'0.0.0.0',espHost:process.env.MAXBOT_ESP_HOST||'192.168.4.1',discover:true})
  server.on('error', error => { console.error(`MaxBot server failed: ${error.message}`); process.exitCode = 1 })
  const stop = () => { for (const peer of websocket.clients) peer.close(1001, 'Server shutdown'); server.close(); setTimeout(() => process.exit(), 600).unref() }
  process.on('SIGTERM', stop); process.on('SIGINT', stop)
}
module.exports = { start }
