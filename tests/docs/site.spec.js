import {test,expect} from 'playwright/test';

test('home guides readers to a complete Java 25 tutorial',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('/');
 await expect(page.getByRole('heading',{name:/Give every release/})).toBeVisible();
 await expect(page.locator('.learning-path a').first()).toHaveCSS('display','grid');
 await page.screenshot({path:'test-results/tutorial-home-desktop.png',fullPage:true});
 await page.getByRole('link',{name:'Start building'}).click();
 await expect(page.getByRole('heading',{name:/^Run the decision room/})).toBeVisible();
 await expect(page.locator('article')).toContainText('JDK 25');
 expect(errors).toEqual([]);
});

test('architecture renders paper diagrams and preserves solid fills in expanded view',async({page})=>{
 const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto('/architecture/');
 await expect(page.locator('.workshop-mermaid[data-rendered="true"]')).toHaveCount(2);
 await expect(page.locator('.workshop-solid-fill').first()).toBeVisible();
 await page.getByRole('button',{name:'Expand diagram',exact:true}).first().click();
 const dialog=page.getByRole('dialog',{name:'Expanded diagram'});await expect(dialog).toBeVisible();
 await expect(dialog.locator('.workshop-solid-fill').first()).toBeVisible();
 await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();expect(errors).toEqual([]);
});

test('mobile home fits the viewport and chapter links work',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
 await page.screenshot({path:'test-results/tutorial-home-mobile.png',fullPage:true});
 await page.getByRole('link',{name:'Connect delivery to Kubernetes'}).click();
 await expect(page.getByRole('heading',{name:/^Connect the demo to Kubernetes/})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});

test('tutorial routes and local assets resolve',async({request})=>{
 const paths=['/','/tutorials/01-local/','/tutorials/02-cluster/','/tutorials/03-investigate/','/tutorials/04-govern/','/architecture/','/reference/agents/','/reference/api/','/reference/observability/','/reference/troubleshooting/','/demo-design/'];
 for(const path of paths){const response=await request.get(path);expect(response.ok(),path).toBeTruthy();}
 for(const path of ['/assets/vendor/mermaid-12.1.0.min.js','/javascripts/diagrams.js','/javascripts/mermaid-solid-fills.js','/stylesheets/tutorial.css']){expect((await request.get(path)).ok(),path).toBeTruthy();}
});

test('tutorial screenshots decode and link to their full-resolution source',async({page})=>{
 for(const chapter of ['02-cluster','03-investigate','04-govern']){
  await page.goto('/tutorials/'+chapter+'/');
  const images=page.locator('article img[src*="images/screenshots/"]');
  expect(await images.count()).toBeGreaterThan(0);
  for(const image of await images.all()){
   await image.scrollIntoViewIfNeeded();
   await expect.poll(()=>image.evaluate(node=>node.complete&&node.naturalWidth>0)).toBeTruthy();
   expect(await image.getAttribute('alt')).toBeTruthy();
   expect(await image.evaluate(node=>node.closest('a')?.href===node.src)).toBeTruthy();
  }
 }
});
