import {test,expect} from 'playwright/test';
async function setup(page){
 const posts=[];
 await page.route('**/api/**',r=>{if(r.request().method()!=='GET')posts.push(r.request().url());return r.fulfill({json:{mode:'fixture',clusterEnabled:false,releaseEnabled:false,latest:{},events:[]}});});
 return posts;
}
test('Production tips explains adoption choices without starting agents or changing the demo',async({page})=>{
 const posts=await setup(page);await page.setViewportSize({width:1440,height:1100});await page.goto('/');await page.getByRole('button',{name:'Production tips'}).click();
 const view=page.locator('#production');await expect(view).toHaveClass('tab active');
 await expect(view).toContainText('automatic investigations are not enabled in this demo');await expect(view).toContainText('automatic investigation does not imply automatic promotion');
 await expect(view).toContainText('Manual investigation');await expect(view).toContainText('Automatic investigation + human review');await expect(view).toContainText('policy-authorized promotion');await expect(view).toContainText('Benefits:');await expect(view).toContainText('Tradeoffs:');
 await expect(view).toContainText('five-second status poll must not become a five-second LLM invocation loop');await expect(view).toContainText('AnalysisRun UID');await expect(view).toContainText('do not keep rerolling the model');
 await expect(view).toContainText('automatic healthy investigation could otherwise produce GO');await expect(view).toContainText('experiment precondition');
 await view.locator('summary').click();await expect(view).toContainText('concurrentExecution=SKIP');await expect(view).toContainText('within one process');await expect(view).toContainText('exit code zero');
 await expect(view.locator('a[href*="tutorials/05-production/"]')).toHaveCount(1);expect(posts).toEqual([]);
 await page.screenshot({path:test.info().outputPath('production-tips-desktop.png')});
});
test('Production tips persists across reload and fits mobile',async({page})=>{
 const posts=await setup(page);await page.setViewportSize({width:390,height:844});await page.goto('/');await page.getByRole('button',{name:'Production tips'}).click();await page.reload();
 await expect(page.locator('#production')).toHaveClass('tab active');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await page.screenshot({path:test.info().outputPath('production-tips-mobile.png')});expect(posts).toEqual([]);
});
