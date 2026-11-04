import {test,expect} from 'playwright/test';
import {readFile} from 'node:fs/promises';
import path from 'node:path';

test('render read-only recorded cluster evidence in the current SPA',async({page})=>{
 test.skip(!process.env.DEMO_SCREENSHOT_SNAPSHOT,'Provide a locally saved read-only cluster snapshot');
 const snapshot=JSON.parse(await readFile(process.env.DEMO_SCREENSHOT_SNAPSHOT,'utf8'));
 expect(snapshot.responses['/api/state'].clusterEnabled).toBe(true);
 await page.clock.install({time:new Date(snapshot.capturedAt)});
 await page.route('**/api/**',async r=>{
  expect(r.request().method(),'Recorded evidence capture must never write to a live service').toBe('GET');
  const url=new URL(r.request().url()),data=snapshot.responses[url.pathname];
  expect(data,url.pathname+' must come from the saved cluster response').toBeDefined();
  await r.fulfill({json:data});
 });
 const destination=path.resolve('docs/images/screenshots');
 await page.goto('/');await expect(page.locator('#rollout-stages li')).toHaveCount(4);
 await expect(page.locator('#rollout-phase')).toContainText(snapshot.responses['/api/progress'].phase);
 await page.locator('.rollout-panel').screenshot({path:path.join(destination,'10-cluster-progress.png')});
 const latest=snapshot.responses['/api/state'].latest;
 if(latest?.decision){
  await page.getByRole('button',{name:'Evidence explorer'}).click();
  await expect(page.locator('.evidence-verdict').last()).toHaveText(latest.decision.effective.replaceAll('_','-'));
  await page.locator('.evidence-decision').screenshot({path:path.join(destination,'08-recorded-decision.png')});
 }
 await page.getByRole('button',{name:'Live console'}).click();await page.locator('#log-source').selectOption('rollouts');
 await expect(page.locator('#console')).not.toBeEmpty();
 await page.locator('#logs').screenshot({path:path.join(destination,'14-controller-logs.png')});
});
