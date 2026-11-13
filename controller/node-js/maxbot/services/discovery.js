'use strict'
const dgram = require('node:dgram')
const { sign, valid } = require('./operator-stop')
/** Authenticated response only to AP-local discovery requests; registration expires in 5 s. */
function discovery ({ key, port, host = '0.0.0.0' }) {
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true })
  socket.on('error', () => {})
  socket.on('message', (bytes, peer) => {
    if (!peer.address.startsWith('192.168.4.') || bytes.length > 512) return
    let m; try { m = JSON.parse(bytes) } catch { return }
    if (m.type !== 'discover' || !/^[a-f0-9]{32}$/.test(m.nonce) || !valid(key, `D|${m.nonce}`, m.mac)) return
    const expires = Date.now() + 5000
    const response = { type: 'registration', nonce: m.nonce, port, expires_ms: expires, mac: sign(key, `R|${m.nonce}|${port}|${expires}`) }
    socket.send(Buffer.from(JSON.stringify(response)), peer.port, peer.address)
  })
  socket.bind(4244, host); return socket
}
module.exports = { discovery }
