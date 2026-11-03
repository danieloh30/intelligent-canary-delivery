import {test,expect} from 'playwright/test';

test('dashboard releases selected version through Git and exposes agent observation views',async({page})=>{
 let releases=0;
 await page.route('**/api/state',route=>route.fulfill({json:{mode:'live',clusterEnabled:true,releaseEnabled:true,latest:{},events:[]}}));
 await page.route('**/api/cluster',route=>route.fulfill({json:{connected:true,rollout:{status:{phase:'Healthy'}}}}));
 await page.route('**/api/release/*',async route=>{
  releases++;expect(route.request().method()).toBe('POST');expect(route.request().url()).toMatch(/\/release\/v3$/);
  await new Promise(resolve=>setTimeout(resolve,250));
  await route.fulfill({json:{version:'v3',sha:'abc123456789',url:'https://github.com/demo/repo/commit/abc123456789'}});
 });
 await page.goto('/');await page.locator('[data-scene="retry"]').click();
 expect(releases).toBe(0);
 await page.locator('#release').click();await expect(page.locator('#release')).toBeDisabled();
 await expect(page.locator('#evaluate')).toBeDisabled();
 await expect(page.locator('#release-result')).toContainText('v3 committed');
 await expect(page.locator('#release-result a')).toHaveAttribute('href','https://github.com/demo/repo/commit/abc123456789');
 await expect(page.locator('#release')).toBeEnabled();expect(releases).toBe(1);
 await expect(page.locator('#overview')).toContainText('start the Quarkus Java agents');
 await page.getByRole('button',{name:'Live console'}).click();
 await expect(page.locator('#log-source option[value="quarkus"]')).toHaveText('Quarkus control plane');
});

test('release failures remain visible and rehearsal disables Git writes',async({page})=>{
 let enabled=true;
 await page.route('**/api/state',route=>route.fulfill({json:{mode:'live',clusterEnabled:false,releaseEnabled:enabled,latest:{},events:[]}}));
 await page.route('**/api/release/*',route=>route.fulfill({status:409,body:'Git changed; refresh before retrying.'}));
 await page.goto('/');await page.locator('#release').click();
 await expect(page.locator('#release-result')).toContainText('Git changed');
 await expect(page.locator('#release')).toBeEnabled();
 enabled=false;await page.reload();await expect(page.locator('#release')).toBeDisabled();
 await expect(page.locator('#release-help')).toContainText('./demo.sh release v2');
});

test('an active investigation disables the release action',async({page})=>{
 await page.route('**/api/state',route=>route.fulfill({json:{mode:'live',clusterEnabled:false,releaseEnabled:true,latest:{status:'RUNNING'},events:[]}}));
 await page.goto('/');await expect(page.locator('#release-help')).toContainText('Commits the rollout');
 await expect(page.locator('#release')).toBeDisabled();
});
