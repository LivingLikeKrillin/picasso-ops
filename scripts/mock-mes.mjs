// Mock MES HTTP client: submit once, then only observe. Never auto-resubmit.
import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const file=process.argv[2];
if(!file || file==='--help') {
 console.log('node scripts/mock-mes.mjs <order.json> [--poll] [--ack]\n--ack prints the observed result and acknowledges its exact version/hash.\nRequires PICASSO_INTEGRATION_TOKEN. Optional MOCK_MES_BASE_URL. No automatic resubmission.');
 process.exit(file ? 0 : 1);
}
const token=process.env.PICASSO_INTEGRATION_TOKEN;
if(!token) throw new Error('PICASSO_INTEGRATION_TOKEN is required');
const env=await fs.readFile(fileURLToPath(new URL('../.env',import.meta.url)),'utf8');
const port=env.split(/\r?\n/).map(line=>line.trim()).find(line=>line.startsWith('OPS_PORT='))?.split('=')[1]?.trim();
if(!process.env.MOCK_MES_BASE_URL && !port) throw new Error('OPS_PORT is missing');
const base=new URL(process.env.MOCK_MES_BASE_URL || `http://127.0.0.1:${port}`);
if(!['http:','https:'].includes(base.protocol) || base.username || base.password) throw new Error('Invalid base URL');
const body=await fs.readFile(file,'utf8');
if(Buffer.byteLength(body)>65536) throw new Error('Order exceeds 64KiB');
JSON.parse(body);
const headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
async function call(path,options={}) {
 const response=await fetch(new URL(path,base),{...options,headers,signal:AbortSignal.timeout(15000)});
 if(!response.ok) throw new Error(`HTTP ${response.status}: ${await response.text()}`);
 return response.json();
}
let answer;
try { answer=await call('/integration/v1/job-orders',{method:'POST',body}); }
catch(e) { console.error('Submission was not retried. If transport failed, query the existing external order by sending the SAME ID and body; never invent a new ID.'); throw e; }
const row=answer.receipt;
console.log(JSON.stringify({receiptId:row.receiptId,externalOrderId:row.externalOrderId,jobOrderId:row.jobOrderId,state:row.state,duplicate:answer.duplicate},null,2));
if(process.argv.includes('--poll') || process.argv.includes('--ack')) {
 let previous='';
 const deadline=Date.now()+120000;
 while(Date.now()<deadline) {
  const view=await call(`/integration/v1/job-orders/${encodeURIComponent(row.receiptId)}`);
  const state=view.execution?.physicalState;
  const line=JSON.stringify({state:view.receipt.state,hostRead:view.hostRead,physicalState:state??null,journalBlocked:view.journalBlocked});
  if(line!==previous) {console.log(line);previous=line;}
  if(['REJECTED','UNASSIGNED'].includes(view.receipt.state)) break;
  if(['PHYSICALLY_DONE','FAILED','UNVERIFIED','ABORTED','PARTIAL'].includes(state) && view.latestResult) {
   const result=view.latestResult;
   console.log(JSON.stringify({result},null,2));
   if(process.argv.includes('--ack')) {
    const ack=await call(`/integration/v1/job-orders/${encodeURIComponent(row.receiptId)}/results/${result.version}/ack`,{method:'POST',body:JSON.stringify({resultHash:result.resultHash})});
    console.log(JSON.stringify({acknowledgedVersion:ack.version,acknowledgedAt:ack.acknowledgedAt},null,2));
   }
   break;
  }
  await new Promise(resolve=>setTimeout(resolve,3000));
 }
}
