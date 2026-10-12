// Isolated local E2E receiver. Fail once per key, then accept duplicates without claiming application ACK.
import { createServer } from 'node:http'
const received=new Map()
createServer(async(req,res)=>{
  if(req.method==='GET' && req.url==='/health'){res.writeHead(200).end('ready');return}
  if(req.method==='GET' && req.url==='/received'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify([...received.values()]));return}
  if(req.method!=='POST' || req.url!=='/results'){res.writeHead(404).end();return}
  if(req.headers.authorization!=='Bearer picasso-local-callback-e2e-only'){res.writeHead(401).end();return}
  const chunks=[];let size=0
  for await(const chunk of req){size+=chunk.length;if(size>65536){res.writeHead(413).end();return}chunks.push(chunk)}
  let value
  try{value=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{res.writeHead(400).end();return}
  const key=req.headers['idempotency-key']
  if(key!==`${value.receiptId}/v${value.version}`){res.writeHead(400).end();return}
  const old=received.get(key)
  if(old && JSON.stringify(old.body)!==JSON.stringify(value)){res.writeHead(409).end();return}
  const record={key,body:value,attempts:(old?.attempts??0)+1}
  received.set(key,record)
  res.writeHead(record.attempts===1?503:204).end()
}).listen(8786,'127.0.0.1',()=>console.log('local mock MES callback ready'))
