const { chromium } = require('playwright'); const path=require('path'); const fs=require('fs');
(async()=>{ const b=await chromium.launch(); const p=await b.newPage({viewport:{width:960,height:540},deviceScaleFactor:2});
 const out=process.argv[2]; fs.mkdirSync(out,{recursive:true});
 for(const f of fs.readdirSync(__dirname).filter(f=>f.endsWith('.html')&&(!process.argv[3]||f.includes(process.argv[3])))){
  await p.goto('file://'+path.join(__dirname,f)); await p.waitForTimeout(400);
  await p.screenshot({path:path.join(out,f.replace('.html','.png'))}); console.log(f);} await b.close();})();
