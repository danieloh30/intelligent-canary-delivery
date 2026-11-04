import {test,expect} from 'playwright/test';
test.beforeEach(async({page})=>{
 await page.route('**/api/state',r=>r.fulfill({json:{mode:'live',clusterEnabled:false,releaseEnabled:false,latest:{},events:[]}}));
});
test('fault injection confirms activation, expiry, restart and clearing beside the button',async({page})=>{
 let seconds=[];
 await page.route('**/api/fault/*',async r=>{const s=Number(r.request().url().split('/').pop());seconds.push(s);await r.fulfill({json:{seconds:s}});});
 await page.clock.install();await page.goto('/');await page.locator('#inject').click();
 const status=page.locator('#fault-result');
 await expect(status).toContainText('Shared slowdown active');await expect(status).toContainText('650 ms');await expect(status).toHaveAttribute('data-state','active');
 await expect(page.locator('#inject')).toHaveText('Restart 25s shared slowdown');
 await page.reload();await expect(status).toContainText('Shared slowdown active');
 await page.clock.fastForward(26000);await expect(status).toContainText('Slowdown expired');await expect(status).toContainText('60-second');
 await page.locator('#inject').click();await expect(status).toHaveAttribute('data-state','active');
 await page.locator('#clear').click();await expect(status).toContainText('Dependency fault cleared');await expect(status).toHaveAttribute('data-state','ended');
 expect(seconds).toEqual([25,25,0]);
});
test('failed fault requests display an inline error and enable retry',async({page})=>{
 await page.route('**/api/fault/*',r=>r.fulfill({status:503,body:'Inventory fault control unavailable'}));
 await page.goto('/');await page.locator('#inject').click();
 await expect(page.locator('#fault-result')).toContainText('Could not update');await expect(page.locator('#fault-result')).toContainText('Inventory fault control unavailable');
 await expect(page.locator('#fault-result')).toHaveAttribute('data-state','error');await expect(page.locator('#inject')).toBeEnabled();
});
