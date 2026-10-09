// Manual browser acceptance fixture: the real console event transport, an HttpOnly cookie,
// small physical packets, and large logical requests/replies/pushes. No database or deployment.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {WebSocketServer} from '../sdk/node/node_modules/ws/wrapper.mjs';
import {fixtureSocket} from './sdk-packet-fixture.mjs';
const require = createRequire(new URL('../plowshare-console/node_modules/vite/package.json', import.meta.url));
const {build} = require('vite');
const root = fileURLToPath(new URL('../', import.meta.url));
await build({configFile:false,root:root+'plowshare-console',build:{lib:{entry:root+'plowshare-console/src/events.ts',formats:['es'],fileName:'events'},outDir:root+'build/packet-browser'}});
let operations=0,packets=0,maxPacket=0;
const text='\u0001'.repeat(5*1024*1024);
const server=createServer(async (request,response)=>{
  if(request.url==='/events.js'){response.writeHead(200,{'Content-Type':'text/javascript'}).end(await readFile(root+'build/packet-browser/events.js'));return;}
  response.writeHead(200,{'Content-Type':'text/html','Set-Cookie':'ps_access=packet-browser-fixture; HttpOnly; SameSite=Strict; Path=/'}).end(`<!doctype html><title>Console packet transport check</title><h1>Console packet transport check</h1><p id="result">Connecting…</p><script type="module">
import {openEventStream} from '/events.js';
const output=document.getElementById('result');
const text='\\u0001'.repeat(5*1024*1024);
let push=false,reply=false;
const done=()=>{if(push&&reply){output.textContent='PASS: cookie upgrade; 5 MiB request, reply and push; packet sizes ≤128 KiB; one operation';stream.close();}};
const stream=openEventStream({session:'browser-packet-fixture',onEvent:event=>{if(event.kind==='fixture.large'){if(event.text!==text)throw Error('push changed');push=true;done();}},onStatus:status=>{if(status.state==='open')stream.ask('fixture.echo',{text}).then(answer=>{if(answer.code!=='OK'||answer.payload.text!==text)throw Error('reply changed');reply=true;done();}).catch(error=>{output.textContent='FAIL: '+error.message;stream.close();});}});
</script>`);
});
const sockets=new WebSocketServer({noServer:true});
server.on('upgrade',(request,socket,head)=>{
  try{assert.equal(request.headers.cookie,'ps_access=packet-browser-fixture');assert.equal(request.headers['sec-websocket-protocol'],'plowshare-segments-v1');}
  catch(error){console.error(error);socket.destroy();return;}
  sockets.handleUpgrade(request,socket,head,physical=>{
    physical.on('message',wire=>{maxPacket=Math.max(maxPacket,wire.length);assert.ok(wire.length<=128*1024);if(JSON.parse(wire.toString()).kind==='transport.segment')packets++;});
    const logical=fixtureSocket(physical);
    logical.on('message',wire=>{
      const sent=JSON.parse(wire.toString());assert.equal(sent.payload.text,text);operations++;assert.equal(operations,1);
      logical.send(JSON.stringify({id:sent.id,type:sent.type,protocol_version:'plowshare-v1',payload:{code:'OK',payload:{text}}}));
      logical.send(JSON.stringify({kind:'fixture.large',text}));
    });
    physical.on('close',()=>{assert.equal(operations,1);assert.ok(packets>480);console.log(JSON.stringify({result:'PASS',operations,packets,maxPacket,cookie:true}));});
  });
});
server.listen(0,'127.0.0.1',()=>console.log('Browser fixture: http://127.0.0.1:'+server.address().port));
