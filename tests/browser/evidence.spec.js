import {test,expect} from 'playwright/test';
function evaluation({expired=false}={}){
 const id='evidence-demo';return {id,status:'COMPLETE',profile:'development',mode:'live',toolReads:['metrics-tool','trace-tool','log-tool','release-tool','runbook-tool'],
 evidence:{id,capturedAt:new Date(Date.now()-(expired?130000:0)).toISOString(),source:'LIVE_KUBERNETES_REQUESTS',revision:'v2',stableRevision:'baseline-hash',rolloutStep:2,stable:{requests:1600,p95Ms:652,errorRate:0,attemptsPerCheckout:1},canary:{requests:196,p95Ms:653,errorRate:0,attemptsPerCheckout:1},traces:[{traceId:'sample-trace',cohort:'canary',latencyMs:2,attempts:1}],logs:['sample-trace attempts=1'],missing:[],releaseNotes:'v2 checkout response optimization'},
 findings:{release:{summary:'Release prerequisites reviewed.',evidenceIds:[id],limitations:[]},dependency:{summary:'Recent samples have significantly lower latency with no retries.',evidenceIds:[id],limitations:['Samples do not establish the shared cause.']},metrics:{summary:'Both revisions have elevated p95.',evidenceIds:[id],limitations:[]}},
 recommendation:{verdict:'HOLD',summary:'Shared degradation requires recovery and fresh evidence.',evidenceIds:[id],limitations:['Shared cause unresolved']},decision:{recommendation:'HOLD',effective:'HOLD',reason:'Recommendation reports unresolved limitations',policy:'development',approvalRequired:false,expiresAt:new Date(Date.now()+(expired?-10000:120000)).toISOString()}};
}
async function setup(page,latest){await page.route('**/api/state',r=>r.fulfill({json:{clusterEnabled:false,releaseEnabled:false,latest,events:[]}}));}
test('explorer explains frozen signals, recommendation, tool receipts and specialist limitations',async({page})=>{
 const e=evaluation();await setup(page,e);await page.goto('/');await page.getByRole('button',{name:'Evidence explorer'}).click();const view=page.locator('#evidence-content');
 await expect(view).toContainText('FROZEN INVESTIGATION EVIDENCE');await expect(view).toContainText('do not update as traffic recovers');
 await expect(view.locator('.evidence-table')).toContainText('652.00 ms');await expect(view.locator('.evidence-table')).toContainText('653.00 ms');
 await expect(view).toContainText('consistent with shared degradation');await expect(view).toContainText('does not by itself prove');
 await expect(view.locator('.evidence-decision')).toContainText('Agent recommendation: HOLD → effective decision: HOLD');await expect(view).toContainText(e.decision.reason);
 expect(await view.locator('.evidence-specialist h3').allTextContents()).toEqual(['Metrics Analyst','Dependency Investigator','Release Analyst']);
 await expect(view.locator('[data-agent="dependency"]')).toContainText('trace-tool: read this frozen investigation');await expect(view.locator('[data-agent="dependency"]')).toContainText('Samples do not establish the shared cause');
 await expect(view.locator('[data-agent="dependency"] mark')).toHaveText(['significantly lower','no retries']);
 await expect(view).toContainText('not a complete distributed trace graph');await expect(view).toContainText('matches this snapshot');
 const details=view.locator('details').first();await details.locator('summary').click();await expect(details).toHaveAttribute('open','');
 await page.waitForTimeout(2300);await expect(details).toHaveAttribute('open','');await expect(details.locator('pre')).toContainText('sample-trace');
 await page.screenshot({path:'/tmp/canary-evidence-expanded.png',fullPage:true});
});
test('expired evidence stays available and raw agent text is rendered safely on mobile',async({page})=>{
 await page.setViewportSize({width:390,height:844});const e=evaluation({expired:true});e.findings.metrics.summary='<img src=x onerror=alert(1)> Untrusted agent text';await setup(page,e);await page.goto('/');await page.getByRole('button',{name:'Evidence explorer'}).click();
 await expect(page.locator('#evidence-content')).toContainText('Decision expired — retained for inspection');await expect(page.locator('#evidence-content')).toContainText('<img src=x');await expect(page.locator('#evidence-content img')).toHaveCount(0);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});
test('page, Shared slowdown scenario and Extended mode survive reload without an investigation',async({page})=>{
 let posts=0;await setup(page,evaluation());await page.route('**/api/evaluate?*',r=>{posts++;return r.fulfill({json:{}});});
 await page.goto('/');await page.locator('#demo-mode').selectOption('extended');await page.locator('[data-scene="slowdown"]').click();await page.getByRole('button',{name:'Evidence explorer'}).click();await page.reload();
 await expect(page.locator('#evidence')).toHaveClass('tab active');await expect(page.locator('[data-scene="slowdown"]')).toHaveClass('scene selected');await expect(page.locator('#demo-mode')).toHaveValue('extended');expect(posts).toBe(0);
 await page.getByRole('button',{name:'Release overview'}).click();await page.locator('[data-scene="missing"]').click();await page.reload();await expect(page.locator('[data-scene="missing"]')).toHaveClass('scene extended selected');
});

test('numerical correction audit preserves original claims and distinguishes recommendation from safety decision',async({page})=>{
 const e=evaluation();e.recommendation.verdict='HOLD';e.decision.effective='NO_GO';e.decision.reason='Retry safety budget exceeded: canary 5 attempts per checkout versus stable 1';
 e.numericalReviews=[{attempt:1,issues:['Sample contradiction: 58 requests >= 30'],report:{recommendation:{verdict:'HOLD',summary:'Insufficient requests'}}},{attempt:2,issues:[],report:{recommendation:{verdict:'NO_GO',summary:'Canary retries confirmed'}}}];
 await setup(page,e);await page.goto('/');await page.getByRole('button',{name:'Evidence explorer'}).click();
 const view=page.locator('#evidence-content');await expect(view.locator('.evidence-decision')).toContainText('Agent recommendation: HOLD → effective decision: NO-GO');
 const audit=view.locator('.numerical-review');await expect(audit).toContainText('Sample contradiction: 58 requests >= 30');await expect(audit).toContainText('No supported numerical contradiction detected');
 await audit.locator('summary').click();await expect(audit.locator('pre')).toContainText('Insufficient requests');await expect(audit.locator('pre')).toContainText('Canary retries confirmed');
});
