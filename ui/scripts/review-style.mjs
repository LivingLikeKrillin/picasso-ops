import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../',import.meta.url)).replaceAll('\\','/').replace(/\/$/,'');
const require=createRequire(root+'/ui/package.json');
const ts=require('typescript');
const {chromium}=require('@playwright/test');
let source=await fs.readFile(root+'/ui/src/testing/fakeOps.ts','utf8');
source=source.replace("import { vi } from 'vitest'", 'const vi = { fn: (f: unknown) => f, stubGlobal: (_name: string, f: unknown) => { (globalThis as any).__fixtureFetch = f } }');
const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
const f=await import('data:text/javascript;base64,'+Buffer.from(compiled).toString('base64'));
const at='2026-10-10T06:30:00Z';
const humanoid={...f.robotView('humanoid-01','CONFIRMED'), commissioning:{state:'COMPLETE',ledgerConfirmed:true,bound:true,siteNamesReady:true},binding:f.binding()};
const quad={...f.robotView('quadruped-01','CONFIRMED'),commissioning:{state:'INCOMPLETE',ledgerConfirmed:true,bound:false,siteNamesReady:false}};
const fake=f.installFakeOps({registry:'OK',checkedAt:at,robots:[humanoid,quad],robotsAsOf:at,settingsVersion:1,connectionThresholdSeconds:90},
f.adapterView({checkedAt:at,asOf:at,adapters:[{adapterId:1,vendor:'acme',name:'fleet',versions:[{adapterVersionId:10,version:'1.0.0',contractSemver:'0.9.0',conformance:'UNTESTED',registeredAt:at,registeredBy:'engineer/local'}]}],instances:[{instanceId:'fleet-gw-01',siteId:'site-01',fleetEndpoint:null,registeredAt:at,registeredBy:'engineer/local',adapter:'acme/fleet',version:'1.0.0',contractSemver:'0.9.0',conformance:'UNTESTED',discoveredRobots:2}]}),
f.profileView({checkedAt:at,asOf:at,catalog:{contractSemver:'0.9.0',skillTypes:[{name:'navigate_to',major:1,introducedInSemver:'0.9.0',siteReferenceKeys:[]}]},revisions:[f.revisionView({status:'ACTIVE',activatedBy:'engineer/local',activatedAt:at,suites:Object.fromEntries(['CONTRACT','NEGATIVE','DETERMINISM'].map(k=>[k,{result:'PASS',ranBy:'site-runner',ranAt:at,detail:{checks:12}}]))},'DONE')]}));
fake.executions=f.executionsView({pumpedAt:at,executions:[{executionId:'exec-1',jobOrderId:'JO-20261010-0001',workMasterId:'PrepareSequencedRack',missionVersion:3,robotId:'humanoid-01',physicalState:'OPERATOR_HOLD',units:[{unitId:'rack-arrival',skillType:'equipment_wait',state:'OPERATOR_HOLD',reached:'E0'}],jobResponse:null}]});
fake.incidents=f.incidentsView([f.incidentRow({at})]);
fake.incidentDetails.set('incident-1',f.incidentDetail({wallClockAt:at}));
fake.mission=f.missionOverview({active:{version:1,source:'DATA',detail:f.versionRow({activatedAt:at})},versions:[f.versionRow({activatedAt:at})],drafts:[f.draftRow({savedAt:at})]});
const out=root+'/docs/screens/style-preview';
await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
const errors=[];
const capture=async options=>{ await page.evaluate(()=>{ document.activeElement?.blur(); window.scrollTo(0,0); }); await page.screenshot(options); };
page.on('pageerror',e=>errors.push(e.message));
await page.route('**/api/**',async route=>{
  const request=route.request();
  const url=new URL(request.url());
  const receipt={receiptId:'receipt-mock-204',sourceSystemId:'mock-mes',externalOrderId:'MES-ORDER-204',jobOrderId:'JO-20261011-example',state:'UNASSIGNED',detail:'기체가 다른 작업을 수행 중입니다',payload:{interfaceId:'asset-check',interfaceVersion:1,input:{assets:[{id:'T1',location:'bay-7'}]}},normalizedOrder:null,submission:null,receivedAt:at,updatedAt:at};
  if(url.pathname==='/api/work-requests' || url.pathname==='/api/work-requests/receipt-mock-204') {
    const body=url.pathname==='/api/work-requests'?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'OK',instanceId:'mw-example',execution:null,journalBlocked:false,observedAt:at};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}); return;
  }
  const response=await globalThis.__fixtureFetch(url.pathname+url.search,{method:request.method(),body:request.postData(),headers:request.headers()});
  await route.fulfill({status:response.status,contentType:'application/json',body:await response.text()});
});
await page.goto('http://127.0.0.1:4179');
await page.getByRole('button',{name:'humanoid-01',exact:true}).click();
await page.evaluate(()=>document.fonts.ready);
await capture({path:out+'/robots-desktop-mock.png',fullPage:true});
await capture({path:out+'/robots-first-view-mock.png',fullPage:false});
await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'임무',exact:true}).click();
await page.getByRole('heading',{name:'서열 랙 준비'}).waitFor();
await capture({path:out+'/missions-desktop-mock.png',fullPage:true});
await page.getByRole('navigation',{name:'미션 관리'}).getByRole('button',{name:'호출 연결',exact:true}).click();
await page.getByRole('table',{name:'입력 필드 매핑'}).waitFor();
await capture({path:out+'/mission-contract-desktop-mock.png',fullPage:true});
await page.getByRole('button',{name:'호출 계약 만들기',exact:true}).click();
await capture({path:out+'/mission-interface-editor-mock.png',fullPage:true});
await page.getByRole('navigation',{name:'미션 관리'}).getByRole('button',{name:'실행 정의',exact:true}).click();
await capture({path:out+'/mission-definition-desktop-mock.png',fullPage:true});
await page.getByRole('navigation',{name:'미션 관리'}).getByRole('button',{name:'버전·배포',exact:true}).click();
await capture({path:out+'/mission-versions-desktop-mock.png',fullPage:true});
await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'운영',exact:true}).click();
await page.getByRole('button',{name:'MES-ORDER-204 접수 보기',exact:true}).click();
await page.getByRole('article',{name:'작업 요청 상세'}).getByText('기체가 다른 작업을 수행 중입니다',{exact:true}).waitFor();
await capture({path:out+'/upstream-receipt-desktop-mock.png',fullPage:true});
await page.getByRole('button',{name:'incident-1 상세 보기',exact:true}).click();
await page.getByText('관측(봉인 때 기록)',{exact:true}).waitFor();
await capture({path:out+'/operations-desktop-mock.png',fullPage:true});
await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'현장 설정',exact:true}).click();
await page.getByRole('form',{name:'현장 설정 변경'}).waitFor();
await capture({path:out+'/site-desktop-mock.png',fullPage:true});
const overflow=[];
for(const width of [1440,1280,768,390]){
  await page.setViewportSize({width,height:900});
  await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'임무',exact:true}).click();
  for (const name of ['미션 목록','실행 정의','호출 연결','버전·배포']) {
    await page.getByRole('navigation',{name:'미션 관리'}).getByRole('button',{name,exact:true}).click();
    if(name==='호출 연결') {
      const create=page.getByRole('button',{name:'호출 계약 만들기',exact:true});
      if(await create.isVisible()) await create.click();
    }
    const sizes=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
    if(sizes.scroll>sizes.width+1) overflow.push({area:name,...sizes});
    if(width===390 && name==='호출 연결') await capture({path:out+'/mission-contract-mobile-mock.png',fullPage:true});
  }
  for(const area of ['로봇','임무','운영','현장 설정','이력','검증 도구']){
    await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:area,exact:true}).click();
    await page.waitForTimeout(100);
    if(area==='운영') {
      await page.getByRole('button',{name:'MES-ORDER-204 접수 보기',exact:true}).click();
      await page.getByRole('article',{name:'작업 요청 상세'}).getByText('기체가 다른 작업을 수행 중입니다',{exact:true}).waitFor();
      if(width===390) await capture({path:out+'/upstream-receipt-mobile-mock.png',fullPage:true});
    }
    const sizes=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
    if(sizes.scroll>sizes.width+1) overflow.push({area,...sizes});
  }
}
await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'로봇',exact:true}).click();
await capture({path:out+'/robots-mobile-mock.png',fullPage:true});
await page.setViewportSize({width:1440,height:1000});
await page.getByRole('button',{name:'로봇 추가',exact:true}).click();
await page.getByRole('radio',{name:/새 제조사/}).check();
await page.getByLabel('준비를 이어갈 기체').selectOption('quadruped-01');
await capture({path:out+'/onboarding-desktop-mock.png',fullPage:true});
await capture({path:out+'/onboarding-first-view-mock.png',fullPage:false});
for(const width of [1440,1280,768,390]) {
  await page.setViewportSize({width,height:900});
  for(const name of ['도입 준비','어댑터 관리','프로파일 관리']) {
    await page.getByRole('navigation',{name:'로봇 관리'}).getByRole('button',{name,exact:true}).click();
    const sizes=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
    if(sizes.scroll>sizes.width+1) overflow.push({area:name,...sizes});
  }
}
await page.getByRole('button',{name:'도입 준비',exact:true}).click();
await capture({path:out+'/onboarding-mobile-mock.png',fullPage:true});
await page.setViewportSize({width:1440,height:1000});
fake.failing.add('/api/robots');
await page.waitForTimeout(5500);
await capture({path:out+'/robots-stale-mock.png',fullPage:true});
const font=await page.evaluate(()=>document.fonts.check('15px Pretendard'));
await browser.close();
console.log(JSON.stringify({errors,overflow,font,output:out},null,2));
if(errors.length || overflow.length || !font) process.exitCode=1;
