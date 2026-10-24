import {test,expect} from 'playwright/test';

test('launcher consumes access fragment and removes it from the address bar',async({page})=>{
 await page.goto('/#access=browser-test');
 await expect(page.locator('#connection')).toHaveText('Offline rehearsal');
 await expect(page.locator('#access-dialog')).not.toBeVisible();
 expect(new URL(page.url()).hash).toBe('');
 expect(await page.evaluate(()=>sessionStorage.getItem('canary-token'))).toBe('browser-test');
 await page.reload();
 await expect(page.locator('#access-dialog')).not.toBeVisible();
});

test('local default connects without a presenter dialog',async({page})=>{
 const credentials=[];
 await page.route('**/api/**',async route=>{
  credentials.push(route.request().headers().authorization);
  await route.fulfill({json:{mode:'fixture',clusterEnabled:false,latest:{},events:[]}});
 });
 await page.goto('/');
 await expect(page.locator('#connection')).toHaveText('Offline rehearsal');
 await expect(page.locator('#access-dialog')).not.toBeVisible();
 expect(credentials[0]).toBe('Bearer demo-local');
});

test('deployed hostname requires access and launcher credential takes precedence',async({page,baseURL})=>{
 await page.route('https://canary.example.test/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.startsWith('/api/')){
   if(route.request().headers().authorization!=='Bearer browser-test')return route.fulfill({status:401});
   return route.fulfill({json:{mode:'live',clusterEnabled:true,latest:{},events:[],connected:true}});
  }
  const response=await route.fetch({url:baseURL+path});
  await route.fulfill({response});
 });
 await page.goto('https://canary.example.test/');
 await expect(page.locator('#access-dialog')).toBeVisible();
 await page.evaluate(()=>sessionStorage.setItem('canary-token','expired-token'));
 await page.goto('https://canary.example.test/#access=browser-test');
 await expect(page.locator('#connection')).toHaveText('Kubernetes connected');
 await expect(page.locator('#access-dialog')).not.toBeVisible();
 expect(new URL(page.url()).hash).toBe('');
});
