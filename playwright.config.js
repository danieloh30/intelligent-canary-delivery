import {defineConfig} from 'playwright/test';
export default defineConfig({testDir:'./tests/browser',use:{baseURL:process.env.DEMO_URL||'http://localhost:8080',screenshot:'only-on-failure'},reporter:'list',workers:1});
