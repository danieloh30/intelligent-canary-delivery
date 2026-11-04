import {test,expect} from 'playwright/test';
test.beforeEach(async({page})=>{await page.route('**/api/progress',r=>r.fulfill({json:{connected:false,state:'UNAVAILABLE',ready:false,message:'Progress not supplied by this fixture.',capturedAt:new Date().toISOString(),stages:[]}}));});

function evaluation(){return {id:'previous-v2',status:'COMPLETE',agents:{metrics:'COMPLETE'},findings:{},evidence:{id:'previous-v2',revision:'v2',stableRevision:'old-stable',rolloutStep:2,capturedAt:new Date().toISOString(),source:'LIVE_KUBERNETES_REQUESTS',traces:[{podHash:'old-canary'}],stable:{requests:100},canary:{requests:40,errorRate:0,p95Ms:490,attemptsPerCheckout:1}},decision:{effective:'HOLD',reason:'Previous release',expiresAt:new Date(Date.now()+120000).toISOString()}};}

test('Core and Extended replace the timer and Core hides the extended scenario',async({page})=>{
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'fixture',clusterEnabled:false,latest:{},events:[]}}));
 await page.goto('/');await expect(page.getByLabel('Demo mode')).toHaveValue('core');
 await expect(page.locator('#clock, #timer')).toHaveCount(0);
 await expect(page.locator('[data-scene="missing"]')).toBeHidden();
 await page.getByLabel('Demo mode').selectOption('extended');await page.locator('[data-scene="missing"]').click();
 await page.getByLabel('Demo mode').selectOption('core');
 await expect(page.locator('[data-scene="missing"]')).toBeHidden();
 await expect(page.locator('[data-scene="healthy"]')).toHaveClass(/selected/);
});

test('reset clears the old verdict, metrics, approvals and evidence and persists after reload',async({page})=>{
 let latest=evaluation(),resets=0;
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'fixture',clusterEnabled:false,resetEnabled:true,latest,events:[]}}));
 await page.route('**/api/reset',r=>{expect(r.request().method()).toBe('POST');latest={};resets++;return r.fulfill({json:{version:'v1',message:'Rehearsal reset.'}});});
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText('HOLD');
 await page.locator('#profile').selectOption('production');await page.getByRole('button',{name:'Reset demo to v1'}).click();
 await expect(page.locator('#effective-verdict')).toHaveText('Awaiting investigation');
 await expect(page.locator('#latency')).toHaveText('—');await expect(page.locator('#static-verdict')).toHaveText('Awaiting signals');
 await expect(page.locator('#revision')).toHaveValue('v1');await expect(page.locator('#profile')).toHaveValue('development');
 await expect(page.locator('#approve')).toBeHidden();
 await page.getByRole('button',{name:'Evidence explorer'}).click();await expect(page.locator('#evidence-content')).not.toContainText('previous-v2');
 await page.reload();await expect(page.locator('#effective-verdict')).toHaveText('Awaiting investigation');await expect(page.locator('#revision')).toHaveValue('v1');expect(resets).toBe(1);
});

for(const mismatch of ['release','stage','expired'])test(`${mismatch} changes invalidate the current verdict while retaining historical evidence`,async({page})=>{
 const latest=evaluation();
 const rollout={spec:{},status:{phase:'Paused',currentPodHash:'old-canary',stableRS:'old-stable',currentStepIndex:2}};
 if(mismatch==='release')rollout.status.currentPodHash='new-v1';
 if(mismatch==='stage')rollout.status.currentStepIndex=5;
 if(mismatch==='expired')latest.decision.expiresAt=new Date(Date.now()-1000).toISOString();
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest,events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,candidateVersion:mismatch==='release'?'v1':'v2',stableVersion:'v1',rollout}}));
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText(mismatch==='expired'?'Decision expired':'Awaiting investigation');
 await expect(page.locator('#latency')).toHaveText('—');await expect(page.locator('#stable-version')).toHaveText('v1');
 await page.getByRole('button',{name:'Evidence explorer'}).click();await expect(page.locator('#evidence-content')).toContainText('previous-v2');
});

test('reset reports pending baseline and Healthy v1 is an empty initial state',async({page})=>{
 let healthy=false;
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest:{},releaseIntent:{version:'v1',releasedAt:'reset',reset:true},events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:'v1',rollout:{spec:{template:{metadata:{annotations:{'demo.canary/released-at':'reset'}}}},status:{phase:healthy?'Healthy':'Paused',stableRS:'stable',currentPodHash:healthy?'stable':'new-v1',currentStepIndex:2}}}}));
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText('Awaiting baseline');
 await expect(page.locator('#revision')).toHaveValue('v1');
 healthy=true;await page.reload();await expect(page.locator('#effective-verdict')).toHaveText('Awaiting investigation');
 await expect(page.locator('#policy-reason')).toContainText('Healthy v1 baseline');await expect(page.locator('#canary-version')).toHaveText('—');
});

test('a newer shell release supersedes an earlier dashboard reset intent',async({page})=>{
 const latest=evaluation();
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest,releaseIntent:{version:'v1',releasedAt:'2026-11-01T00:00:00Z',reset:true},events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,stableVersion:'v1',candidateVersion:'v2',rollout:{spec:{template:{metadata:{annotations:{'demo.canary/released-at':'2026-11-02T00:00:00+00:00'}}}},status:{phase:'Paused',stableRS:'old-stable',currentPodHash:'old-canary',currentStepIndex:2}}}}));
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText('HOLD');
 await expect(page.locator('#latency')).toHaveText('490');
});

test('NO-GO stays visible after abort resets the step and evidence expires, then clears for a new release',async({page})=>{
 const latest=evaluation();latest.decision.effective='NO_GO';latest.decision.reason='Canary-specific retry amplification';latest.recommendation={verdict:'NO_GO',summary:'Canary-specific retry amplification',limitations:[],evidenceIds:[latest.id]};
 const rollout={spec:{},status:{phase:'Progressing',currentPodHash:'old-canary',stableRS:'old-stable',currentStepIndex:2,abort:false}};
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest,events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,candidateVersion:'v2',stableVersion:'v1',rollout}}));
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText('NO-GO');
 rollout.status.phase='Degraded';rollout.status.abort=true;rollout.status.currentStepIndex=0;latest.decision.expiresAt=new Date(Date.now()-1000).toISOString();
 await expect(page.locator('#policy-reason')).toContainText('Recorded NO-GO for this aborted rollout',{timeout:10000});
 await expect(page.locator('#effective-verdict')).toHaveText('NO-GO');await expect(page.locator('#policy-reason')).toContainText('Evidence expired');await expect(page.locator('#approve')).toBeHidden();
 await page.locator('[data-tab="evidence"]').click();await expect(page.locator('.evidence-verdict')).toHaveText(['NO-GO','NO-GO']);await expect(page.locator('.evidence-verdict').first()).toHaveCSS('background-color','rgb(251, 228, 223)');
 await page.locator('[data-tab="overview"]').click();rollout.status.currentPodHash='new-canary';rollout.status.phase='Progressing';rollout.status.abort=false;
 await expect(page.locator('#effective-verdict')).toHaveText('Awaiting investigation',{timeout:10000});
});

test('Missing evidence HOLD stays current without trace rows and clears when rollout identity changes',async({page})=>{
 const latest=evaluation();latest.evidence.canaryPodHash='old-canary';latest.evidence.traces=[];
 latest.evidence.missing=['Presenter-injected trace evidence outage'];latest.decision.reason='Required evidence missing: Presenter-injected trace evidence outage';latest.recommendation={verdict:'HOLD',summary:'Missing required trace evidence',limitations:['Trace evidence unavailable'],evidenceIds:[latest.id]};
 const rollout={spec:{},status:{phase:'Progressing',currentPodHash:'old-canary',stableRS:'old-stable',currentStepIndex:2}};
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:true,latest,events:[]}}));
 await page.route('**/api/cluster',r=>r.fulfill({json:{connected:true,candidateVersion:'v2',stableVersion:'v1',rollout}}));
 await page.goto('/');await expect(page.locator('#effective-verdict')).toHaveText('HOLD');await expect(page.locator('#static-verdict')).toHaveText('PASS');await expect(page.locator('#latency')).toHaveText('490');
 await expect(page.locator('#policy-reason')).toContainText('trace evidence outage');await expect(page.locator('#approve')).toBeHidden();
 await page.locator('[data-tab="evidence"]').click();await expect(page.locator('.evidence-snapshot')).not.toContainText('Historical investigation');await expect(page.locator('.evidence-verdict')).toHaveText(['HOLD','HOLD']);
 await page.reload();await expect(page.locator('#effective-verdict')).toHaveText('HOLD');
 rollout.status.currentPodHash='new-candidate';await expect(page.locator('#effective-verdict')).toHaveText('Awaiting investigation',{timeout:10000});
});
