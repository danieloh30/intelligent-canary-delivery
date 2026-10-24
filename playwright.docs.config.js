import {defineConfig} from 'playwright/test';
export default defineConfig({
 testDir:'./tests/docs',
 use:{baseURL:'http://127.0.0.1:8766',screenshot:'only-on-failure'},
 webServer:{command:'python3 -m http.server 8766 --bind 127.0.0.1 --directory site',url:'http://127.0.0.1:8766',reuseExistingServer:false},
 reporter:'list',workers:1
});
