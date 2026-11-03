import {test,expect} from 'playwright/test';

for(const [phase,background,color] of [
 ['Healthy','rgb(24, 117, 73)','rgb(255, 255, 255)'],
 ['Paused','rgb(255, 240, 194)','rgb(116, 76, 0)'],
 ['Progressing','rgb(255, 240, 194)','rgb(116, 76, 0)'],
 ['Degraded','rgb(251, 228, 223)','rgb(144, 46, 37)'],
 ['Unavailable','rgb(238, 242, 245)','rgb(86, 103, 117)']
])test(`rollout ${phase} has a clear text label and distinct status color`,async({page})=>{
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest:{},events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:phase==='Unavailable'?{connected:false,message:'Cluster unavailable'}:{connected:true,stableVersion:'v1',candidateVersion:'v1',rollout:{status:{phase,currentPodHash:'stable',stableRS:'stable'}}}}));
 await page.goto('/');const badge=page.locator('#rollout-phase');
 await expect(badge).toHaveText('Rollout: '+phase);await expect(badge).toHaveAttribute('role','status');
 await expect(badge).toHaveCSS('background-color',background);await expect(badge).toHaveCSS('color',color);
 await expect(page.locator('.stable-identity .version-label')).toHaveCSS('color','rgb(22, 92, 55)');
});

test('an active canary is blue and an absent canary has a neutral explicit description',async({page})=>{
 let active=true;
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest:{},events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:active?'v2':'v1',rollout:{status:{phase:active?'Progressing':'Healthy',currentPodHash:active?'new':'stable',stableRS:'stable'}}}}));
 await page.goto('/');await expect(page.locator('#canary-version')).toHaveText('v2');
 await expect(page.locator('#canary-identity .version-label')).toHaveCSS('color','rgb(35, 79, 146)');
 await expect(page.locator('#canary-description')).toHaveText('Candidate under evaluation');
 active=false;await page.reload();await expect(page.locator('#canary-description')).toHaveText('No active canary');
 await expect(page.locator('#canary-version')).toHaveText('—');
 await expect(page.locator('#canary-identity .version-label')).toHaveCSS('color','rgb(92, 110, 121)');
});

test('connection failure clears a previously green health badge',async({page})=>{
 let disconnected=false;
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,releaseIntent:{version:'v1',releasedAt:'2026-11-03T00:00:00Z'},latest:{},events:[]}}));
 await page.route('**/api/cluster',r=>disconnected?r.fulfill({status:503,body:'Cluster read failed'}):r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:'v1',rollout:{spec:{template:{metadata:{annotations:{'demo.canary/released-at':'2026-11-03T00:00:00Z'}}}},status:{phase:'Healthy',currentPodHash:'stable',stableRS:'stable'}}}}));
 await page.goto('/');await expect(page.locator('#rollout-phase')).toHaveText('Rollout: Healthy');
 disconnected=true;await expect(page.locator('#rollout-phase')).toHaveText('Rollout: Unavailable',{timeout:10000});
 await expect(page.locator('#rollout-phase')).toHaveCSS('background-color','rgb(238, 242, 245)');
});

test('colored labels fit a mobile viewport',async({page})=>{
 await page.setViewportSize({width:390,height:844});
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest:{},events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:'v2',rollout:{status:{phase:'Progressing',currentPodHash:'new',stableRS:'stable'}}}}));
 await page.goto('/');await expect(page.locator('#rollout-phase')).toHaveText('Rollout: Progressing');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});
