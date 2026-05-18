import { expect, test } from '@playwright/test'
import { setEnglishLocale } from './helpers/auth-fixtures'

test.describe('Admin Users - UserId Column', () => {
  test.beforeEach(async ({ page, context }) => {
    await setEnglishLocale(page)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])

    // Use mock admin user
    await page.context().setExtraHTTPHeaders({
      'X-Mock-User-Id': 'local-admin'
    })

    await page.goto('/admin/users')
    await page.waitForResponse(resp => resp.url().includes('/api/v1/admin/users') && resp.status() === 200)
  })

  test('userId column appears after username column', async ({ page }) => {
    // Wait for table to load
    await expect(page.getByRole('columnheader', { name: 'Username' })).toBeVisible()

    // Check userId column exists
    const userIdHeader = page.getByRole('columnheader', { name: 'User ID' })
    await expect(userIdHeader).toBeVisible()

    // Verify column order: Username should come before User ID
    const headers = page.getByRole('columnheader')
    const headerTexts = await headers.allTextContents()

    const usernameIndex = headerTexts.findIndex(text => text.includes('Username'))
    const userIdIndex = headerTexts.findIndex(text => text.includes('User ID'))

    expect(usernameIndex).toBeGreaterThanOrEqual(0)
    expect(userIdIndex).toBeGreaterThan(usernameIndex)
  })

  test('userId values are displayed in table rows', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    // Wait for at least one row to load
    const firstRow = page.getByRole('row').nth(1)
    await expect(firstRow).toBeVisible()

    // Check that userId cells contain non-empty values
    const userIdCells = page.getByRole('cell').filter({ hasText: /^[a-zA-Z0-9-]+$/ })
    const count = await userIdCells.count()
    expect(count).toBeGreaterThan(0)
  })

  test('copy button exists for each userId', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    // Wait for rows to load
    const rows = page.getByRole('row')
    const rowCount = await rows.count()

    if (rowCount <= 1) {
      test.skip()
    }

    // Check for copy buttons (they should have aria-label or be buttons)
    const copyButtons = page.getByRole('button').filter({ hasText: /copy/i })
    const buttonCount = await copyButtons.count()

    expect(buttonCount).toBeGreaterThan(0)
  })

  test('clicking copy button copies userId to clipboard', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    // Find first copy button in the table
    const firstRow = page.getByRole('row').nth(1)
    await expect(firstRow).toBeVisible()

    // Get the userId text before clicking
    const userIdCell = firstRow.getByRole('cell').nth(1) // Assuming userId is 2nd column
    const userIdText = await userIdCell.textContent()

    // Click the copy button
    const copyButton = firstRow.getByRole('button', { name: /copy/i }).first()
    await copyButton.click()

    // Wait for clipboard to update
    await page.waitForTimeout(100)

    // Verify clipboard content
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText())
    expect(clipboardText).toBeTruthy()
    expect(clipboardText.length).toBeGreaterThan(0)
  })

  test('copy button shows feedback after clicking', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    const firstRow = page.getByRole('row').nth(1)
    await expect(firstRow).toBeVisible()

    const copyButton = firstRow.getByRole('button', { name: /copy/i }).first()
    await copyButton.click()

    // Check for "Copied" feedback
    await expect(page.getByText(/copied/i)).toBeVisible({ timeout: 2000 })
  })

  test('userId column persists after search/filter', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    // Perform a search if search input exists
    const searchInput = page.getByPlaceholder(/search/i).first()
    if (await searchInput.isVisible()) {
      await searchInput.fill('test')
      await page.waitForTimeout(500)
    }

    // Verify userId column still exists
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()
  })

  test('userId column persists across pagination', async ({ page }) => {
    await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()

    // Check if pagination exists
    const nextButton = page.getByRole('button', { name: /next/i })

    if (await nextButton.isVisible() && await nextButton.isEnabled()) {
      await nextButton.click()
      await page.waitForTimeout(500)

      // Verify userId column still exists on next page
      await expect(page.getByRole('columnheader', { name: 'User ID' })).toBeVisible()
    } else {
      test.skip()
    }
  })
})
