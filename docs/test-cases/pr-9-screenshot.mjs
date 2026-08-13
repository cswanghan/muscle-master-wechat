import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { homedir } from 'node:os'

function loadPlaywright() {
  const roots = [
    process.env.PLAYWRIGHT_PKG,
    path.join(homedir(), '.npm/_npx/fd3bca3c548369c0/package.json'),
    path.join(homedir(), '.npm/_npx/e41f203b7505f1fb/package.json'),
  ].filter(Boolean)
  for (const root of roots) {
    if (!existsSync(root)) continue
    try {
      return createRequire(root)('playwright')
    } catch {
      // try next cache
    }
  }
  throw new Error('playwright not found; set PLAYWRIGHT_PKG to a package.json near it')
}

const { chromium } = loadPlaywright()

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const outDir = path.join(__dirname, 'screenshots')
const url = process.env.PREVIEW_URL || 'http://127.0.0.1:8765/docs/test-cases/c-end-preview.html'

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.CHROME_PATH || undefined,
})
const page = await browser.newPage({ viewport: { width: 980, height: 920 } })
await page.goto(url, { waitUntil: 'networkidle' })
await page.waitForSelector('#c1-home')
await page.waitForSelector('#entry-symptom')
await page.locator('#phone').screenshot({ path: path.join(outDir, 'pr-9-c1-home.png') })

await page.locator('#entry-store').click()
await page.waitForSelector('[data-store]')
await page.locator('[data-store]').first().click()
await page.waitForSelector('[data-proj]')
await page.locator('[data-proj]').first().click()
await page.waitForSelector('#c3-calendar')
await page.waitForSelector('.slot.free')
await page.waitForSelector('.slot.locked')
await page.waitForSelector('.slot.booked')
await page.locator('#phone').screenshot({ path: path.join(outDir, 'pr-9-c3-calendar.png') })

await page.locator('.card:has-text("陈默") .slot.free').first().click()
await page.locator('#go-confirm').click()
await page.waitForSelector('#c4-confirm')
await page.locator('#lock-btn').click()
await page.waitForSelector('#countdown')
await page.waitForTimeout(400)
await page.locator('#phone').screenshot({ path: path.join(outDir, 'pr-9-c4-confirm.png') })

await page.evaluate(() => { location.hash = 'mine'; })
await page.waitForSelector('#c6-mine')
await page.waitForSelector('#no-wallet')
await page.waitForTimeout(400)
await page.locator('#phone').screenshot({ path: path.join(outDir, 'pr-9-c6-mine.png') })

await browser.close()
console.log('wrote', outDir)
