import {defineConfig} from 'playwright/test';
export default defineConfig({
 testDir:'./tests/screenshots',workers:1,reporter:'list',
 use:{baseURL:process.env.DEMO_URL||'http://localhost:8095',viewport:{width:1440,height:1100},deviceScaleFactor:1},
});
