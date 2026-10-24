import {test,expect} from 'playwright/test';
test.beforeEach(async({page})=>{await page.addInitScript(()=>sessionStorage.setItem('canary-token','browser-test'));});
test('retry investigation shows green basic checks and governed NO-GO',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/');
 await page.locator('[data-scene="retry"]').click();
 await page.getByRole('button',{name:'Run investigation'}).click();
 await expect(page.locator('#effective-verdict')).toHaveText('NO-GO');
 await expect(page.locator('#static-verdict')).toHaveText('PASS');
 await expect(page.locator('#attempts')).toHaveText('5.00×');
 await page.getByRole('button',{name:'Evidence explorer'}).click();
 await expect(page.locator('#evidence-content')).toContainText('REHEARSAL_FIXTURE');
 expect(errors).toEqual([]);
});
test('production approval changes HOLD to GO',async({page})=>{
 await page.goto('/');await page.locator('#profile').selectOption('production');
 await page.locator('[data-scene="healthy"]').click();
 await page.getByRole('button',{name:'Run investigation'}).click();
 await expect(page.getByRole('button',{name:'Approve promotion'})).toBeVisible();
 await expect(page.locator('#effective-verdict')).toHaveText('HOLD');
 await page.getByRole('button',{name:'Approve promotion'}).click();
 await expect(page.locator('#effective-verdict')).toHaveText('GO');
});
test('extended mode exposes missing evidence and mobile fits',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 await page.locator('#duration').selectOption('15');
 await page.locator('[data-scene="missing"]').click();
 await page.getByRole('button',{name:'Run investigation'}).click();
 await expect(page.locator('#effective-verdict')).toHaveText('HOLD');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await page.getByRole('button',{name:'Live console'}).click();
 await expect(page.locator('#console')).toContainText('policy');
});

test('architecture diagrams use paper style and expand without changing fills',async({page})=>{
 await page.goto('/');await page.getByRole('button',{name:'Architecture',exact:false}).click();
 await expect(page.locator('.workshop-mermaid[data-rendered="true"]')).toHaveCount(2);
 const first=page.locator('.workshop-mermaid svg').first();
 await expect(first.locator('.rough-node')).not.toHaveCount(0);
 await expect(first.locator('.workshop-solid-fill')).not.toHaveCount(0);
 await page.getByRole('button',{name:'Expand diagram',exact:true}).first().click();
 const dialog=page.getByRole('dialog',{name:'Expanded diagram'});await expect(dialog).toBeVisible();
 await expect(dialog.locator('.workshop-solid-fill')).not.toHaveCount(0);
 await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();
});
