import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  timeout: 60_000,
  retries: 0,

  // 所有产物统一落到 demo-recordings/（视频、截图、trace）
  outputDir: './demo-recordings',

  use: {
    baseURL: 'http://localhost:9173',
    trace: 'on-first-retry',
    video: 'on',               // 录视频（webm，每个 test 一个文件）
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
