// vitest.config.ts
import { defineConfig } from 'vitest/config'
import path from 'path'

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/renderer/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/renderer/**/*.{ts,tsx}'],
      exclude: ['src/renderer/test/**', 'src/renderer/**/*.test.*']
    }
  },
  resolve: {
    alias: {
      // Aligned with the source of truth for this alias, which lives in
      // electron.vite.config.ts (resolve.alias → "@" = src/renderer).
      // The source of truth is NOT in this file — edit it there, mirror here.
      '@': path.resolve(__dirname, 'src/renderer'),
    }
  }
})
