import {test,expect} from 'playwright/test';

async function setup(page,{step=2,weight=10,state='READY',ready=true,stable=40,canary=35}={}){
 const data={step,weight,state,ready,stable,canary,phase:'Progressing',latest:{},checks:12,analysis:'Running',missing:[],offline:false,stale:false,posts:[]};
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,releaseEnabled:true,resetEnabled:true,latest:data.latest,events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:'v2',rollout:{status:{phase:data.phase,currentPodHash:'candidate',stableRS:'stable',currentStepIndex:data.step}}}}));
 await page.route('**/api/progress',r=>r.fulfill({json:{connected:!data.offline,state:data.offline?'UNAVAILABLE':data.state,message:data.offline?'Cannot read live progress.':data.state==='WARMUP'?'The 15s warm-up is before the analysis gate; no click is needed during the pause.':data.state==='COLLECTING'?'Collect at least 30 observations for BOTH stable and canary.':data.state==='INCONCLUSIVE'?'This analysis has finished Inconclusive. Request a fresh release.':'The analysis gate is waiting for your decision.',phase:data.phase,revision:'v2',podHash:'candidate',stepIndex:data.step,targetWeight:data.weight,pauseDuration:'15s',analysisName:data.state==='WARMUP'?'':'analysis-current',analysisPhase:data.analysis,checksCompleted:data.checks,checksTotal:180,interval:'5s',capturedAt:new Date(Date.now()-(data.stale?20000:0)).toISOString(),stableRequests:data.stable,canaryRequests:data.canary,samplesAvailable:true,ready:data.ready,missing:data.missing,stages:[{weight:10,state:data.weight===10?'active':'complete'},{weight:50,state:data.weight===50?'active':'pending'},{weight:100,state:'pending'}]}}));
 await page.route('**/api/evaluate?*',r=>{data.posts.push(r.request().url());data.latest={status:'RUNNING'};return r.fulfill({json:{id:'new-investigation',status:'RUNNING'}});});
 return data;
}

test('the warm-up is explicit and a persistent ready cue appears at the analysis gate',async({page})=>{
 const d=await setup(page,{step:1,state:'WARMUP',ready:false});await page.goto('/');
 await expect(page.locator('#progress-title')).toHaveText('Warm-up before analysis · 10% canary');
 await expect(page.locator('#evaluate')).toBeDisabled();await expect(page.locator('#analysis-timing')).toContainText('not a click deadline');
 d.step=2;d.state='READY';d.ready=true;
 await expect(page.locator('#progress-title')).toHaveText('Ready to investigate · 10% canary',{timeout:10000});
 await expect(page.locator('#stable-samples')).toHaveText('40');await expect(page.locator('#canary-samples')).toHaveText('35');
 await expect(page.locator('#progress-investigate')).toBeEnabled();expect(d.posts).toEqual([]);
 await page.locator('#progress-investigate').click();expect(d.posts).toHaveLength(1);expect(d.posts[0]).toContain('revision=v2');
 await expect(page.locator('#evaluate')).toBeDisabled();
});

test('counts are live before the first investigation and readiness returns at fifty percent',async({page})=>{
 const d=await setup(page,{state:'COLLECTING',ready:false,stable:100,canary:8});await page.goto('/');
 await expect(page.locator('#canary-samples')).toHaveText('8');await expect(page.locator('#evaluate')).toBeDisabled();
 d.canary=30;d.state='READY';d.ready=true;
 await expect(page.locator('#progress-title')).toHaveText('Ready to investigate · 10% canary',{timeout:10000});
 await expect(page.locator('#evaluate')).toBeEnabled();expect(d.posts).toEqual([]);
 d.step=5;d.weight=50;
 await expect(page.locator('#progress-title')).toHaveText('Ready to investigate · 50% canary',{timeout:10000});
 await expect(page.locator('#progress-investigate')).toHaveText('Investigate v2 at 50%');
 await expect(page.locator('#rollout-stages .active')).toHaveText('50% canary');
 await expect(page.locator('#traffic-caption')).toContainText('Target: 50% canary');
});

test('finished Inconclusive analysis cannot advertise a ready investigation',async({page})=>{
 const d=await setup(page,{state:'INCONCLUSIVE',ready:false});d.analysis='Inconclusive';d.checks=180;
 await page.goto('/');await expect(page.locator('#progress-title')).toContainText('fresh release required');
 await expect(page.locator('#analysis-status')).toContainText('180 / 180 checks');await expect(page.locator('#analysis-timing')).toContainText('cannot restart');
 await expect(page.locator('#evaluate')).toBeDisabled();await expect(page.locator('#release')).toBeEnabled();expect(d.posts).toEqual([]);
});

test('stale or unavailable progress and wrong candidate selection disable the investigation',async({page})=>{
 const d=await setup(page);d.stale=true;await page.goto('/');await expect(page.locator('#evaluate')).toBeDisabled();
 await expect(page.locator('#progress-title')).toContainText('Refreshing');
 d.stale=false;await page.reload();await expect(page.locator('#evaluate')).toBeEnabled();
 await page.locator('#revision').selectOption('v3');await expect(page.locator('#progress-title')).toContainText('Select the active candidate');await expect(page.locator('#evaluate')).toBeDisabled();
 d.offline=true;await page.reload();await expect(page.locator('#progress-title')).toContainText('Live progress unavailable');await expect(page.locator('#evaluate')).toBeDisabled();
});

test('live progress and the investigate cue fit a mobile viewport',async({page})=>{
 await page.setViewportSize({width:390,height:844});await setup(page);await page.goto('/');
 await expect(page.locator('#progress-investigate')).toBeEnabled();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});

test('Decision Policy explanation exposes safety requirements and existing HITL approval',async({page})=>{
 await setup(page);await page.goto('/');
 const policy=page.locator('.policy-summary');await policy.locator('summary').click();
 await expect(policy).toContainText('30 requests per revision');await expect(policy).toContainText('120-second expiry');
 await expect(policy).toContainText('limitations remain unresolved');await expect(policy).toContainText('error and latency safety limits');
 await expect(policy).toContainText('human approval (HITL)');await expect(policy).toContainText('cannot override');
});

test('presenter guide explains manual gates, the analysis budget, and Extended HITL',async({page})=>{
 await setup(page);await page.goto('/');await page.getByRole('button',{name:'Presenter guide'}).click();
 const guide=page.locator('#guide');
 await expect(guide).toContainText('15s pause is warm-up');await expect(guide).toContainText('180 HTTP checks at 5s intervals');
 await expect(guide).toContainText('30 live requests in BOTH');await expect(guide).toContainText('run a fresh investigation');
 await expect(guide).toContainText('Production / approval');await expect(guide).toContainText('Approve promotion');
 await expect(guide).toContainText('DecisionPolicy.java');await expect(guide).toContainText('presenter-triggered');
});
