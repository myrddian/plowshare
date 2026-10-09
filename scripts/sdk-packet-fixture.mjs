import {createHash, randomUUID} from 'node:crypto';
import {packetSocket, PACKET_PROTOCOL} from '../sdk/typescript/build/binding/packets.js';

/** The fixture uses the public neutral packet owner; assertions remain at the logical SDK boundary. */
export function fixtureSocket(physical) {
  if (physical.protocol !== PACKET_PROTOCOL) return physical;
  const socket=packetSocket({socket:physical,codec:{
    encode:text=>new TextEncoder().encode(text),
    decode:bytes=>new TextDecoder('utf-8',{fatal:true}).decode(bytes),
    base64:bytes=>Buffer.from(bytes).toString('base64'),
    unbase64:text=>new Uint8Array(Buffer.from(text,'base64')),
    hash:bytes=>Promise.resolve(createHash('sha256').update(bytes).digest('hex')),
    identity:randomUUID,
  },schedule:(expired,ms)=>{const timer=setTimeout(expired,ms);return()=>clearTimeout(timer);}});
  return {
    get readyState(){return physical.readyState;},
    on(event,listener){if(event==='message')socket.addEventListener('message',event=>listener(Buffer.from(event.data)));else physical.on(event,listener);},
    send:frame=>socket.send(frame),
    terminate:()=>physical.terminate(),
    close:()=>socket.close(),
  };
}
