import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
const require=createRequire(new URL('../package.json',import.meta.url));
const {chromium}=require('@playwright/test');
const out=fileURLToPath(new URL('../../docs/screens/style-preview/',import.meta.url));
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 await page.goto('http://127.0.0.1:4173');
 await page.getByRole('navigation',{name:'영역'}).getByRole('button',{name:'운영',exact:true}).click();
 const requests=page.getByRole('region',{name:'상위 작업 요청'});
 await requests.getByRole('button',{name:'MES-E2E-204 접수 보기',exact:true}).click({timeout:180000});
 await requests.getByRole('article',{name:'작업 요청 상세'}).getByText('PHYSICALLY_DONE',{exact:true}).waitFor({timeout:120000});
 await page.evaluate(()=>document.fonts.ready);
 await page.evaluate(()=>{document.activeElement?.blur();window.scrollTo(0,0)});
 await requests.screenshot({path:out+'upstream-receipt-fullstack.png'});
 await page.setViewportSize({width:390,height:900});
 await requests.screenshot({path:out+'upstream-receipt-mobile-fullstack.png'});
 const width=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));
 console.log(JSON.stringify({source:'actual Ops HTTP, no route interception',capturedAt:new Date().toISOString(),...width}));
 if(width.scroll>width.width+1)process.exitCode=1;
}finally{await browser.close()}
