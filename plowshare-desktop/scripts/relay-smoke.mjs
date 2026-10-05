import {_electron as electron, expect} from 'playwright/test';
import assert from 'node:assert/strict';
import {mkdtemp,rm,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {protocolFixture} from './protocol-fixture.mjs';
const profile=await mkdtemp(join(tmpdir(),'plowshare-relay-'));
let refuse=false;
let branchState='UNCERTAIN';
let subscriberSeen='9007199254740991';
const generation='26e48bfd-0664-47ab-b3a1-88984c02e8fa';
const topic={generation,name:'schedule.due',kind:'SCHEDULE_DUE',retentionSeconds:'345600',maxRecords:null,through:'9007199254740994',expiredThrough:'9007199254740992'};
const at='2026-10-05T01:00:00Z';
const fixture=await protocolFixture({serverAdmin:true,relay:(type,payload)=>{
  if(refuse) return {code:'BAD_REQUEST',said:'Relay fixture temporarily unavailable'};
  if(payload.system) {
    const nativeTopic={generation,name:'board.wake.requested',kind:'WAKE_REQUESTED',retentionSeconds:'345600',maxRecords:null,through:'3',expiredThrough:'2'};
    const scope={project:null,system:true};
    if(type==='relay.topics') return {code:'OK',payload:{scope,topics:[nativeTopic]}};
    return {code:'OK',payload:{scope,topic:nativeTopic,after:'0',next:'3',gapThrough:'2',
      events:[{position:'3',eventId:'source:fixture',publisher:'system.source',occurredAt:at,publishedAt:at,correlationId:null,causationId:null,
        payload:{kind:'WAKE_REQUESTED',text:null,schedule:null,emits:null,fireAt:null,lifecycle:null,wake:{firing:'fir_fixture',target:'conversation:cnv_fixture',type:'BOARD'}}}],
      subscribers:[{generation,name:'builtin.wakes.fixture',seenThrough:'3',seenAt:at,gapThrough:null}],
      branches:[],recoveries:[{subscriber:'builtin.wakes.fixture',expiredThrough:'2',pendingInbox:'1',recoveredAt:at}]}};
  }
  if(type==='relay.operate') {
    let status;
    if(payload.action==='ACKNOWLEDGE_GAP') {subscriberSeen=payload.expiredThrough;status='GAP_ACKNOWLEDGED';}
    else if(payload.action==='RECONCILE') status=branchState;
    else if(payload.action==='ABANDON') {branchState='ABANDONED_UNCERTAIN';status=branchState;}
    else return {code:'BAD_REQUEST',said:'Retained fixture input prevents removal'};
    return {code:'OK',payload:{requestId:payload.requestId,project:payload.project,topic:payload.topic,action:payload.action,subscriber:payload.subscriber??null,deliveryId:payload.deliveryId??null,status,seenThrough:payload.action==='ACKNOWLEDGE_GAP'?subscriberSeen:null,completedAt:at}};
  }
  if(type==='relay.topics') return {code:'OK',payload:{scope:{project:payload.project,system:false},topics:[topic]}};
  const after=payload.after??'0', position=after==='0'?'9007199254740993':'9007199254740994';
  return {code:'OK',payload:{scope:{project:payload.project,system:false},topic,after,next:position,gapThrough:after==='0'?topic.expiredThrough:null,
    events:[{position,eventId:'schedule:fixture',publisher:'fixture',occurredAt:at,publishedAt:at,correlationId:null,causationId:null,payload:{kind:'SCHEDULE_DUE',text:null,schedule:'<img src=x onerror="window.fixtureInjection=true">',emits:'review',fireAt:at}}],
    subscribers:[{generation,name:'relay.notices.release',seenThrough:subscriberSeen,seenAt:at,gapThrough:BigInt(subscriberSeen)<BigInt(topic.expiredThrough)?topic.expiredThrough:null}],
    branches:[{id:'26e48bfd-0664-47ab-b3a1-88984c02e8fa',position:'9007199254740993',subscriber:'relay.notices.release',name:'review',receiver:'script.run',state:branchState,fence:'1',updatedAt:at,failure:branchState==='UNCERTAIN'?'dispatch.unknown':'operator.abandoned',receiptNamespace:null,receiptId:null,conversation:'fixture-relay-handler',conversationProject:'Relay test',routingHash:'a'.repeat(64),handlerHash:'b'.repeat(64)}]}};
}});
fixture.addServerProject({name:'Relay test'});
const env={...process.env,PLOWSHARE_DESKTOP_PROFILE:profile,PLOWSHARE_DESKTOP_CONFIG:join(profile,'config'),PLOWSHARE_CONFIG_DIR:join(profile,'credentials')};delete env.ELECTRON_RUN_AS_NODE;
let app;
try {
  app=await electron.launch({executablePath:(await import('electron')).default,args:[resolve('.')],env});
  app.process().stderr.on('data', bytes => process.stderr.write(bytes));
  await app.evaluate(() => {
    process.on('uncaughtExceptionMonitor', error => console.error('Relay smoke main exception:', error.stack));
  });
  const main=await app.firstWindow();
  await main.evaluate(base=>window.plowshare.request({action:'connect',base,handle:'fixture',password:'fixture-password'}),fixture.base);
  await main.locator('#relay-open').click();
  await expect.poll(()=>app.windows().some(page=>page.url().includes('relay.html'))).toBe(true);
  const relay=app.windows().find(page=>page.url().includes('relay.html'));
  assert.ok(relay);
  await relay.locator('#relay-scope').selectOption('Relay test');
  await expect(relay.locator('#relay-events')).toContainText('9007199254740993');
  await expect(relay.locator('#relay-gap')).toContainText('9007199254740992');
  await expect(relay.locator('#relay-branches')).toContainText('UNCERTAIN');
  assert.equal(await relay.locator('#relay-events img').count(),0);
  assert.equal(await relay.evaluate(()=>window.fixtureInjection),undefined);
  assert.equal(fixture.frames.filter(frame=>frame.type==='relay.operate').length,0,'Reading must not mutate offsets or deliveries.');
  const gap=relay.locator('.relay-operation').filter({has:relay.getByRole('button',{name:'Acknowledge gap through 9007199254740992'})});
  await gap.locator('input').fill('Acknowledge expired fixture work');
  await gap.locator('button').click();
  await expect(relay.locator('#relay-notice')).toContainText('GAP_ACKNOWLEDGED');
  await expect(relay.getByRole('button',{name:'Acknowledge gap through 9007199254740992'})).toHaveCount(0);
  const reconcile=relay.locator('.relay-operation').filter({has:relay.getByRole('button',{name:'Reconcile receipt'})});
  await reconcile.locator('input').fill('Inspect owning receipt'); await reconcile.locator('button').click();
  await expect(relay.locator('#relay-notice')).toContainText('Reconcile receipt: UNCERTAIN');
  const abandon=relay.locator('.relay-operation').filter({has:relay.getByRole('button',{name:'Abandon delivery'})});
  await abandon.locator('input').fill('Stop considering unresolved fixture work'); await abandon.locator('button').click();
  await expect(relay.locator('#relay-notice')).toContainText('ABANDONED_UNCERTAIN');
  await expect(relay.locator('#relay-branches')).toContainText('ABANDONED_UNCERTAIN');
  assert.equal(fixture.frames.filter(frame=>frame.type==='relay.operate').length,3);
  await relay.locator('#relay-more').click();
  await expect(relay.locator('#relay-events')).toContainText('9007199254740994');
  await expect(relay.locator('#relay-gap')).toBeEmpty();
  await expect(relay.locator('#relay-more')).toBeDisabled();
  refuse=true;await relay.locator('#relay-refresh').click();
  await expect(relay.locator('#relay-error')).toContainText('Last snapshot retained');
  await expect(relay.locator('#relay-events')).toContainText('9007199254740994');
  await expect(relay.locator('#relay-policy')).toContainText('Relay test · schedule.due');
  await expect(relay.locator('.relay-operation button').first()).toBeDisabled();
  refuse=false;await relay.getByRole('button',{name:'Open trajectory'}).click();
  await expect.poll(()=>app.windows().some(page=>page.url().includes('trajectory.html'))).toBe(true);
  const trajectory=app.windows().find(page=>page.url().includes('trajectory.html'));
  assert.ok(trajectory);
  await expect.poll(()=>fixture.frames.some(frame=>frame.type==='conversation.follow' && frame.payload.conversations?.includes('fixture-relay-handler'))).toBe(true);
  await expect(main.locator('.breadcrumb')).toContainText('Relay test');
  await expect.poll(()=>fixture.frames.some(frame=>frame.type==='conversation.trajectory' && frame.payload.conversation==='fixture-relay-handler')).toBe(true);
  await expect(trajectory.locator('#trajectory-error')).toBeHidden();
  await main.evaluate(()=>window.plowshare.request({action:'refresh'}));
  await expect(trajectory.locator('#trajectory-error')).toBeHidden();
  const denied=await main.evaluate(async()=>{try{await window.plowshare.request({action:'relay-read',type:'relay.topics',payload:{project:'Relay test'}});return false;}catch{return true;}});
  assert.equal(denied,true);
  assert.equal(fixture.frames.filter(frame=>frame.type==='relay.process').length,0,'The viewer must not dispatch consumer work.');
  const deniedControl=await main.evaluate(async()=>{try{await window.plowshare.request({action:'relay-operate',payload:{requestId:'26e48bfd-0664-47ab-b3a1-88984c02e8fa',project:'Relay test',topic:'schedule.due',topicGeneration:'26e48bfd-0664-47ab-b3a1-88984c02e8fa',action:'REMOVE_TOPIC',reason:'fixture'}});return false;}catch{return true;}});
  assert.equal(deniedControl,true);
  assert.equal(fixture.frames.filter(frame=>frame.type==='relay.operate').length,3);
  await main.locator('#relay-open').click();
  await relay.locator('#relay-scope').selectOption(':system:');
  await expect(relay.locator('#relay-events')).toContainText('fir_fixture');
  await expect(relay.locator('#relay-subscribers')).toContainText('Recovered availability through 2');
  await expect(relay.locator('#relay-subscribers')).toContainText('owning inbox (1 queued)');
  await expect(relay.locator('.relay-operation')).toHaveCount(0);
  await mkdir('build/smoke',{recursive:true});await relay.screenshot({path:'build/smoke/relay.png'});
  console.log('Relay desktop smoke passed: real WS/IPC, 64-bit paging, gaps, escaping, retained snapshots owned trajectory links explicit fenced controls and native inbox recovery audits.');
} finally {
  if(app) {
    let timer;
    try {
      await Promise.race([
        app.close(),
        new Promise(resolve => { timer=setTimeout(() => { app.process().kill('SIGKILL'); resolve(); },5000); })
      ]);
    } finally { clearTimeout(timer); }
  }
  await fixture.close();await rm(profile,{recursive:true,force:true});
}
