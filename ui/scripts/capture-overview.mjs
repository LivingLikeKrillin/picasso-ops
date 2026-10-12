import { chromium } from '@playwright/test'
import { fileURLToPath } from 'node:url'
const out=fileURLToPath(new URL('../../docs/screens/style-preview/',import.meta.url))
const browser=await chromium.launch({headless:true})
try {
 const page=await browser.newPage({viewport:{width:1440,height:1000}})
 const errors=[];page.on('pageerror',error=>errors.push(error.message))
 await page.goto(`${process.env.OPS_UI_URL??'http://127.0.0.1:4173'}/#overview`)
 const overview=page.getByRole('region',{name:'운영 개요',exact:true})
 await overview.getByRole('link',{name:/전체 접수 [0-9]+/}).waitFor()
 await overview.getByText('수집 중',{exact:true}).waitFor()
 await page.evaluate(()=>document.fonts.ready)
 for(const [width,name] of [[1440,'overview-fullstack.png'],[390,'overview-mobile-fullstack.png']]) {
  await page.setViewportSize({width,height:1000})
  await overview.screenshot({path:out+name,style:'.skip-link{display:none!important}'})
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1)
  if(overflow)throw new Error(`문서 가로 넘침: ${width}`)
 }
 if(errors.length)throw new Error(errors.join('\n'))
 console.log('actual Ops HTTP overview desktop/mobile: no browser errors or document overflow')
}finally{await browser.close()}
