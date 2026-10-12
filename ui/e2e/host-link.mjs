// Local E2E HTTP fault proxy. Production code has no fault-control endpoint.
import http from 'node:http'
import {readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
const root=fileURLToPath(new URL('../..',import.meta.url))
const env=Object.fromEntries(readFileSync(`${root}/.env`,'utf8').split(/\r?\n/).filter(line=>line.includes('=')&&!line.trim().startsWith('#')).map(line=>{const at=line.indexOf('=');return [line.slice(0,at).trim(),line.slice(at+1).trim()]}))
let online=true,cutAfterSubmit=false,submissions=0
const sockets=new Set()
const proxy=http.createServer((req,res)=>{
 if(!online){res.destroy();return}
 const upstream=http.request({hostname:'127.0.0.1',port:Number(env.HOST_PORT),path:req.url,method:req.method,headers:req.headers},reply=>{
  if(req.method==='POST' && req.url==='/host/job-orders'){
   submissions++
   if(cutAfterSubmit && reply.statusCode===200){cutAfterSubmit=false;online=false;reply.resume();for(const socket of sockets)socket.destroy();return}
  }
  res.writeHead(reply.statusCode??502,reply.headers);reply.pipe(res)
 })
 upstream.on('error',()=>res.destroy());req.on('aborted',()=>upstream.destroy());req.pipe(upstream)
})
proxy.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket))})
proxy.listen(8791,'127.0.0.1')
http.createServer((req,res)=>{
 if(req.method==='POST'){
  if(req.url==='/connect')online=true
  else if(req.url==='/disconnect'){online=false;for(const socket of sockets)socket.destroy()}
  else if(req.url==='/cut-after-submit')cutAfterSubmit=true
  else {res.writeHead(404).end();return}
 }
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify({online,cutAfterSubmit,submissions}))
}).listen(8790,'127.0.0.1')
