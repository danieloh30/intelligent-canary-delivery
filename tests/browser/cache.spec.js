import {test,expect} from 'playwright/test';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

test('an ordinary reload bypasses legacy immutable JS and CSS after a dashboard deployment',async({page})=>{
 const root=fileURLToPath(new URL('../../src/main/resources/META-INF/resources/',import.meta.url));
 let legacy=true,oldScripts=0,newScripts=0,newStyles=0;
 const server=createServer(async(req,res)=>{
  try{
   const url=new URL(req.url,'http://localhost');
   res.setHeader('Cache-Control','no-store');
   if(url.pathname==='/api/state'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({mode:'live',clusterEnabled:true,releaseEnabled:true,latest:{},events:[]}));}
   if(url.pathname==='/api/cluster'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({connected:true,stableVersion:'v1',candidateVersion:'v2',rollout:{status:{phase:'Progressing',currentPodHash:'candidate',stableRS:'stable',currentStepIndex:2}}}));}
   if(url.pathname==='/api/progress'){res.setHeader('Content-Type','application/json');return res.end(JSON.stringify({connected:true,state:'READY',message:'The analysis gate is waiting for your decision.',phase:'Progressing',revision:'v2',podHash:'candidate',stepIndex:2,targetWeight:10,pauseDuration:'15s',analysisPhase:'Running',analysisName:'current-analysis',checksCompleted:5,checksTotal:180,interval:'5s',capturedAt:new Date().toISOString(),stableRequests:200,canaryRequests:40,samplesAvailable:true,ready:true,stages:[{weight:10,state:'active'},{weight:50,state:'pending'},{weight:100,state:'pending'}]}));}
   if(url.pathname==='/'&&legacy){res.setHeader('Content-Type','text/html');return res.end('<link rel="stylesheet" href="/styles.css"><div id="legacy-page">Previous dashboard</div><script src="/app.js"></script>');}
   if(url.pathname==='/app.js'&&!url.search){oldScripts++;res.setHeader('Cache-Control','public, immutable, max-age=86400');res.setHeader('Content-Type','application/javascript');return res.end('window.legacyAssetLoaded=true;');}
   if(url.pathname==='/styles.css'&&!url.search){res.setHeader('Cache-Control','public, immutable, max-age=86400');res.setHeader('Content-Type','text/css');return res.end('.investigation-cue{background:red}');}
   if(url.pathname==='/app.js')newScripts++;
   if(url.pathname==='/styles.css')newStyles++;
   const path=resolve(root,'.'+(url.pathname==='/'?'/index.html':url.pathname));
   if(!path.startsWith(root)){res.statusCode=404;return res.end();}
   res.setHeader('Content-Type',path.endsWith('.js')?'application/javascript':path.endsWith('.css')?'text/css':path.endsWith('.html')?'text/html':'text/plain');
   res.end(await readFile(path));
  }catch{res.statusCode=404;res.end();}
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 try{
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  expect(await page.evaluate(()=>window.legacyAssetLoaded)).toBe(true);expect(oldScripts).toBe(1);
  legacy=false;await page.reload();
  await expect(page.locator('#progress-title')).toHaveText('Ready to investigate · 10% canary');
  await expect(page.locator('#evaluate')).toHaveText('Investigate v2 at 10%');await expect(page.locator('#evaluate')).toBeEnabled();
  await expect(page.locator('#evaluate')).toHaveCount(1);await expect(page.locator('#progress-investigate')).toHaveCount(0);
  await expect(page.locator('#investigation-cue')).toHaveCSS('background-color','rgb(226, 244, 233)');
  expect(newScripts).toBeGreaterThan(0);expect(newStyles).toBeGreaterThan(0);expect(oldScripts).toBe(1);
  expect(await page.evaluate(()=>window.legacyAssetLoaded)).toBeUndefined();
 }finally{await page.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
