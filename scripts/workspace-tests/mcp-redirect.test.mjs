import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {pathToFileURL} from 'node:url';
const vendor=process.env.PD_DSH_VENDOR_ROOT;
assert.ok(vendor,'PD_DSH_VENDOR_ROOT must point to the pinned public dependency tree');
const {StreamableHTTPClientTransport}=await import(pathToFileURL(vendor+'/node_modules/@modelcontextprotocol/client/dist/index.mjs'));
async function listening(handler){
 const server=createServer(handler);
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 return {server,url:`http://127.0.0.1:${server.address().port}/mcp`,close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}};
}
const reply=(res)=>{res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({jsonrpc:'2.0',id:1,result:{}}));};
const message={jsonrpc:'2.0',id:1,method:'ping'};
test('MCP transport keeps ordinary requests working but never forwards a custom header to a redirect origin', {timeout:8000},async()=>{
 let direct=0,redirect=0,leaked=0;
 const other=await listening((req,res)=>{if(req.headers['x-fixture-key'])leaked++;reply(res);});
 const origin=await listening((req,res)=>{
  if(req.url==='/direct'){direct++;assert.equal(req.headers['x-fixture-key'],'SYNTHETIC_ONLY');reply(res);}
  else {redirect++;res.writeHead(307,{location:other.url});res.end();}
 });
 const options={requestInit:{headers:{'X-Fixture-Key':'SYNTHETIC_ONLY'}}};
 const normal=new StreamableHTTPClientTransport(new URL(origin.url.replace('/mcp','/direct')),options);
 const redirected=new StreamableHTTPClientTransport(new URL(origin.url),options);
 normal.onmessage=()=>{};redirected.onmessage=()=>{};
 try{
  await normal.start();await normal.send(message);assert.equal(direct,1);
  await redirected.start();
  let rejected=false;try{await redirected.send(message);}catch{rejected=true;}
  assert.equal(redirect,1,'the real redirect entry point must be reached');
  assert.equal(leaked,0,'custom headers must not reach a different origin');
  assert.ok(rejected,'a blocked redirect must remain a visible transport failure');
 }finally{await normal.close();await redirected.close();await origin.close();await other.close();}
});
