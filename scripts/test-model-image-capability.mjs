#!/usr/bin/env node
// Opt-in diagnostic for the dedicated model; never uses personal application settings.
import fs from 'node:fs';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.slice(2).join(' ') !== '--run') {
 console.error('Usage: node scripts/test-model-image-capability.mjs --run (one real model request)');
 process.exit(2);
}
let c;
try {
 c=JSON.parse(fs.readFileSync(path.join(root,'.local/test-model.json'),'utf8'));
 if (!['baseUrl','apiKey','modelId'].every(k=>typeof c[k]==='string'&&c[k].trim())) throw Error();
 const u=new URL(c.baseUrl);
 if (!['https:','http:'].includes(u.protocol)||u.username||u.password||u.search||u.hash) throw Error();
} catch { console.error('Dedicated test configuration invalid; contents suppressed'); process.exit(2); }
const runId=crypto.randomUUID();
const logs=path.join(root,'.local/logs');
fs.mkdirSync(logs,{recursive:true});
function crc(b){let n=0xffffffff;for(const v of b){n^=v;for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0);}return(n^0xffffffff)>>>0;}
function chunk(type,data){const b=Buffer.concat([Buffer.from(type),data]); const n=Buffer.alloc(4),s=Buffer.alloc(4);n.writeUInt32BE(data.length);s.writeUInt32BE(crc(b));return Buffer.concat([n,b,s]);}
const palette=[['red',[255,0,0]],['blue',[0,0,255]],['green',[0,180,0]],['yellow',[255,255,0]]];
const left=crypto.randomInt(4);let right;do{right=crypto.randomInt(4)}while(right===left);
const raw=Buffer.alloc(128*(256*3+1));for(let y=0;y<128;y++)for(let x=0;x<256;x++)palette[x<128?left:right][1].forEach((v,k)=>raw[y*(769)+1+x*3+k]=v);
const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(256,0);ihdr.writeUInt32BE(128,4);ihdr[8]=8;ihdr[9]=2;
const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
const base=c.baseUrl.replace(/\/$/,'');
const url=(base.endsWith('/v1')?base:base+'/v1')+'/responses';
let evidence={purpose:'dedicated-model-image-capability-probe',runId,passed:false};
try{
 const r=await fetch(url,{method:'POST',headers:{Authorization:`Bearer ${c.apiKey}`,'Content-Type':'application/json'},body:JSON.stringify({model:c.modelId,input:[{role:'user',content:[{type:'input_text',text:'Identify the two solid color blocks in this image. Return only the English color names in left-to-right order, separated by a comma. No tools.'},{type:'input_image',image_url:'data:image/png;base64,'+png.toString('base64')}]}],max_output_tokens:512}),signal:AbortSignal.timeout(90000)});
 const j=await r.json();const text=(j.output||[]).flatMap(x=>x.content||[]).filter(x=>x.type==='output_text').map(x=>x.text).join(' ').toLowerCase();
 const names=text.match(/red|blue|green|yellow/g)||[];
 evidence={...evidence,httpStatus:r.status,responseStatus:j.status||null,expectedColors:[palette[left][0],palette[right][0]],recognizedColors:names,outputTextPresent:!!text,matchedLeft:names[0]===palette[left][0],matchedRight:names[1]===palette[right][0],passed:r.ok&&names.length===2&&names[0]===palette[left][0]&&names[1]===palette[right][0]};
}catch{evidence.error='Request or response processing failed; details suppressed';}
// Only allowlisted colors/booleans/status are persisted, never raw response bodies or configuration.
const encoded=JSON.stringify(evidence,null,2);
fs.writeFileSync(path.join(logs,`model-image-probe-${runId}.json`),encoded,{mode:0o600});
fs.writeFileSync(path.join(logs,'model-image-probe.json'),encoded,{mode:0o600});
console.log(JSON.stringify(evidence));
process.exitCode=evidence.passed?0:1;
