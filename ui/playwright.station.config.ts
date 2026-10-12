import { defineConfig } from '@playwright/test'
import base from './playwright.config'

// Separate invocation creates a fresh stack after lifecycle shuts registry down.
export default defineConfig({ ...base, testMatch: '**/station.spec.ts' })
