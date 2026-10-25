import {test,expect} from 'playwright/test';
import {mkdir} from 'node:fs/promises';
import path from 'node:path';

test('capture explicitly labeled local rehearsal checkpoints',async({page})=>{
 const token=process.env.DEMO_API_TOKEN||'demo-local';
 await page.addInitScript(value=>sessionStorage.setItem('canary-token',value),token);
 await page.goto('/');
 // Never inject faults or evaluations into a live cluster during asset generation.
 const state=await page.evaluate(async value=>(await fetch('/api/state',{headers:{Authorization:'Bearer '+value}})).json(),token);
 expect(state.mode,'Start ./demo.sh rehearsal before capturing tutorial images').toBe('fixture');
 expect(state.clusterEnabled,'Screenshot generation must never control a live rollout').toBe(false);
 const destination=path.resolve('docs/images/screenshots');await mkdir(destination,{recursive:true});
 const shot=async name=>{await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({path:path.join(destination,name),fullPage:true});};
 const investigate=async(scene,verdict)=>{
  await page.locator(`[data-scene="${scene}"]`).click();
  await page.getByRole('button',{name:'Run investigation'}).click();
  await expect(page.locator('#effective-verdict')).toHaveText(verdict);
  await expect(page.locator('#evaluate')).toBeEnabled();
 };
 await investigate('healthy','GO');await shot('01-baseline.png');
 await investigate('slowdown','HOLD');await shot('02-shared-hold.png');
 await investigate('healthy','GO');await shot('03-recovered-go.png');
 await investigate('retry','NO-GO');await shot('04-retry-no-go.png');
 await page.getByRole('button',{name:'Evidence explorer'}).click();
 await expect(page.locator('#evidence-content')).toContainText('REHEARSAL_FIXTURE');
 await shot('05-evidence.png');
 await page.getByRole('button',{name:'Release overview'}).click();
 await page.locator('#duration').selectOption('15');
 await page.locator('#profile').selectOption('production');
 await investigate('healthy','HOLD');
 await expect(page.getByRole('button',{name:'Approve promotion'})).toBeVisible();
 await shot('06-production-approval.png');
});
