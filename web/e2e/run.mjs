// End-to-end check of the dashboard against a running backend (no mocks).
//
//   E2E_BASE=http://localhost:8000 E2E_ADMIN_EMAIL=admin@example.com E2E_ADMIN_PASSWORD=... npm run e2e
//
// Creates a new workspace with every module, signs in to it and walks through each page, doing the
// main task on most of them. Fails on any page error, console error or server error (5xx), and on
// unexpected 4xx API responses. Screenshots go to e2e/screens/ (desktop and phone width).
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = process.env.E2E_BASE ?? 'http://localhost:8000'
const ADMIN = process.env.E2E_ADMIN_EMAIL ?? 'admin@example.com'
const ADMIN_PW = process.env.E2E_ADMIN_PASSWORD
if (!ADMIN_PW) { console.error('Set E2E_ADMIN_PASSWORD (the platform admin password).'); process.exit(2) }
const SHOTS = new URL('./screens/', import.meta.url).pathname
mkdirSync(SHOTS, { recursive: true })

const slug = 'e2e-' + Math.random().toString(36).slice(2, 7)
const ownerEmail = `owner@${slug}.test`
const ownerPw = 'Correct-Horse-Battery-9'
const problems = []
const passed = []
let allowed4xx = [] // [method, path-regex] pairs a step expects

const browser = await chromium.launch()
const ctx = await browser.newContext({ viewport: { width: 1360, height: 900 } })
const page = await ctx.newPage()
page.setDefaultTimeout(15000)
page.on('pageerror', (e) => problems.push(`page error on ${page.url()}: ${e.message}`))
page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) problems.push(`console error on ${page.url()}: ${m.text()}`) })
page.on('response', (r) => {
  const u = new URL(r.url())
  if (!u.pathname.startsWith('/api/')) { if (r.status() >= 400 && !u.pathname.startsWith('/assets/')) problems.push(`${r.status()} for page ${u.pathname}`); return }
  if (r.status() >= 500) problems.push(`${r.status()} ${r.request().method()} ${u.pathname}`)
  else if (r.status() >= 400 && !allowed4xx.some(([m, re]) => m === r.request().method() && re.test(u.pathname)))
    r.text().then((t) => problems.push(`${r.status()} ${r.request().method()} ${u.pathname}${u.search} ${t.slice(0, 200)}`)).catch(() => {})
})

async function step(name, fn) {
  const before = problems.length
  try { await fn(); await page.waitForTimeout(150) } catch (e) { problems.push(`${name}: ${e.message.split('\n')[0]}`) }
  if (problems.length === before) passed.push(name); else console.log(`✗ ${name}\n  ${problems.slice(before).join('\n  ')}`)
}
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png`, fullPage: true })
const go = async (path) => { await page.goto(BASE + path); await page.waitForLoadState('networkidle') }
const toast = (re) => page.locator('.toasts').getByText(re).first().waitFor()
const modal = () => page.getByRole('dialog')

// ---------------------------------------------------------------------------------------------------
// Platform console
// ---------------------------------------------------------------------------------------------------
await step('platform sign-in', async () => {
  await go('/platform/login')
  await page.getByLabel('Email').fill(ADMIN)
  await page.getByLabel('Password').fill(ADMIN_PW)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL(/\/platform(\/mfa)?$/)
  if (page.url().endsWith('/mfa')) throw new Error('This admin must set up two-factor first; use a development stack (TC_PLATFORM_MFA_REQUIRED=false).')
  await page.getByRole('heading', { name: 'Workspaces' }).waitFor()
  await shot('p-01-workspaces')
})

await step('create a workspace with every module', async () => {
  await page.getByRole('button', { name: 'New workspace' }).click()
  await page.getByLabel('Business name').fill('E2E Coffee')
  await page.getByLabel('Workspace short name').fill(slug)
  await page.getByLabel('Owner email').fill(ownerEmail)
  await page.getByLabel("Owner's first password").fill(ownerPw)
  await modal().locator('.mod').filter({ hasText: 'QR codes' }).waitFor() // the module list has loaded
  for (const box of await modal().locator('.mod input[type=checkbox]').all()) if (!(await box.isChecked())) await box.check()
  if (await modal().locator('.mod input:not(:checked)').count()) throw new Error('not every module could be switched on')
  await shot('p-02-new-workspace')
  await modal().getByRole('button', { name: 'Create workspace' }).click()
  await page.waitForURL(/\/platform\/tenants\//)
  await page.getByRole('heading', { name: 'E2E Coffee' }).waitFor()
  await shot('p-03-workspace')
})

await step('workspace modules and options', async () => {
  await page.getByRole('tab', { name: 'Modules' }).click()
  await page.getByText('Custom data tables').first().waitFor()
  await page.getByRole('button', { name: 'Options for Files & images' }).click()
  await modal().getByLabel('Options (JSON)').fill('{ "quota_mb": 512 }')
  await modal().getByRole('button', { name: 'Save' }).click()
  await toast(/options saved/i)
  await shot('p-04-modules')
  await page.getByRole('tab', { name: 'Business settings' }).click()
  await page.getByLabel('Currency').fill('THB')
  await page.getByRole('button', { name: 'Save' }).click()
  await toast(/Saved/)
  await page.getByRole('tab', { name: 'Data & offboarding' }).click()
  await page.getByText('Offboarding').first().waitFor()
  await shot('p-05-data')
})

for (const [path, heading, n] of [['/platform/admins', 'Platform admins', '06'], ['/platform/jobs', 'Background jobs', '07'], ['/platform/backups', 'Backups', '08'],
  ['/platform/audit', 'Activity log', '09'], ['/platform/email', 'Email', '10'], ['/platform/storage', 'File storage', '11'], ['/platform/settings', 'General', '12']]) {
  await step(`platform page ${path}`, async () => { await go(path); await page.getByRole('heading', { name: heading, exact: true }).waitFor(); await shot(`p-${n}-${path.split('/').pop()}`) })
}
await step('platform email templates and log tabs', async () => {
  await go('/platform/email')
  await page.getByRole('tab', { name: 'Templates' }).click()
  await page.getByText('Invitation').first().click()
  await modal().getByRole('tab', { name: 'Preview' }).click()
  await modal().locator('iframe').waitFor()
  await modal().getByRole('button', { name: 'Cancel' }).click()
  await page.getByRole('tab', { name: 'Sent mail' }).click()
})
await step('platform MFA setup page shows a QR code', async () => {
  await go('/platform/mfa')
  await page.getByRole('button', { name: 'Set up now' }).click()
  await page.locator('.qr svg').waitFor()
  await shot('p-13-mfa')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await page.waitForURL(/\/platform\/login/)
})

// ---------------------------------------------------------------------------------------------------
// Workspace
// ---------------------------------------------------------------------------------------------------
await step('workspace sign-in', async () => {
  await go('/login')
  await page.getByLabel('Workspace').fill(slug)
  await page.getByLabel('Email').fill(ownerEmail)
  await page.getByLabel('Password').fill(ownerPw)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL(BASE + '/')
  await page.getByText(/Good (morning|afternoon|evening)/).waitFor()
  await shot('t-01-home')
})

await step('settings: brand colour and PromptPay', async () => {
  await go('/settings/general')
  await page.getByLabel('Colour code').fill('#0f766e')
  await page.getByRole('button', { name: 'Save' }).click()
  await toast(/Saved/)
  await shot('t-02-settings')
  await page.getByRole('button', { name: 'Maps & QR' }).click()
  await page.getByLabel('PromptPay ID').fill('0812345678')
  await page.locator('form').filter({ hasText: 'PromptPay ID' }).getByRole('button', { name: 'Save' }).click()
  await toast(/PromptPay saved/)
  for (const tab of ['Orders & checkout', 'Payments', 'Customer accounts', 'Email', 'Push', 'Modules', 'Export data']) {
    await page.getByRole('button', { name: tab, exact: true }).click()
    await page.waitForLoadState('networkidle')
  }
  await shot('t-03-settings-export')
})

await step('payments: switch on the sandbox gateway', async () => {
  await go('/settings/payments')
  const card = page.locator('section.card').filter({ hasText: 'Sandbox' })
  await card.getByText(/Take payments with Sandbox/).click()
  await card.getByRole('button', { name: 'Save' }).click()
  await toast(/saved/i)
})

await step('inventory: add a warehouse', async () => {
  await go('/inventory')
  await page.getByRole('button', { name: 'Add warehouse' }).first().click()
  await modal().getByLabel('Code').fill('MAIN')
  await modal().getByLabel('Name').fill('Main shop')
  await modal().getByRole('button', { name: 'Save' }).click()
  await toast(/Warehouse saved/)
})

await step('products: create a product with two variants', async () => {
  await go('/products/new')
  await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Iced Latte')
  for (const [sku, name, price] of [['LAT-S', 'Small', '60'], ['LAT-L', 'Large', '80']]) {
    await page.getByRole('button', { name: 'Add variant' }).click()
    await modal().getByLabel('SKU').fill(sku)
    await modal().getByRole('textbox', { name: 'Name', exact: true }).fill(name)
    await modal().getByLabel('Price').fill(price)
    await modal().getByRole('button', { name: 'Save variant' }).click()
  }
  await page.getByLabel('Visibility').selectOption('active')
  await page.getByRole('button', { name: 'Create product' }).click()
  await toast(/Product created/)
  await page.waitForURL(/\/products\/[0-9a-f-]{36}$/)
  await shot('t-04-product')
  await go('/products')
  await page.getByText('Iced Latte').waitFor()
  await shot('t-05-products')
})

await step('inventory: adjust stock', async () => {
  await go('/inventory')
  await page.getByRole('row', { name: /LAT-L/ }).getByRole('button', { name: 'Adjust' }).click()
  await modal().getByLabel('Change by').fill('25')
  await modal().getByRole('button', { name: 'Save' }).click()
  await toast(/Stock updated/)
  await page.getByRole('tab', { name: 'Movements' }).click()
  await page.getByText('+25').waitFor()
  await page.getByRole('tab', { name: 'Warehouses' }).click()
  await shot('t-06-inventory')
})

let orderUrl = ''
await step('orders: create an order, take a payment, ship it', async () => {
  await go('/orders')
  await page.getByRole('button', { name: 'New order' }).click()
  await modal().getByLabel('Add a product').fill('Iced')
  await modal().getByRole('option', { name: /Iced Latte – Large/ }).click()
  await modal().getByLabel('Customer name').fill('Ann Example')
  await modal().getByRole('button', { name: 'Create order' }).click()
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/)
  orderUrl = page.url()
  await page.getByRole('button', { name: 'Take payment' }).click()
  await modal().getByRole('button', { name: 'Continue' }).click()
  await toast(/Payment recorded/)
  await page.getByRole('button', { name: 'PromptPay QR' }).waitFor({ state: 'detached' }).catch(() => {})
  await page.getByRole('button', { name: /Mark Shipped/ }).click()
  await toast(/Marked Shipped/)
  await shot('t-07-order')
  await go('/orders')
  await shot('t-08-orders')
})

await step('returns: start and approve a return', async () => {
  await page.goto(orderUrl)
  await page.getByRole('link', { name: 'Start a return for this order' }).click()
  await modal().locator('input.qty').first().fill('1')
  await modal().getByLabel('Reason').fill('Too sweet')
  await modal().getByRole('button', { name: 'Create return' }).click()
  await toast(/Return created/)
  await page.getByRole('button', { name: 'Approve' }).click()
  await modal().getByRole('button', { name: 'Approve' }).click()
  await toast(/Return approved/)
  await shot('t-09-returns')
})

await step('deliveries: create from the order and mark picked up', async () => {
  await page.goto(orderUrl)
  await page.getByRole('button', { name: 'Create delivery' }).click()
  await toast(/Delivery created/)
  await go('/deliveries')
  await page.getByRole('row').nth(1).click()
  await page.getByRole('button', { name: 'Picked up' }).click()
  await toast(/Marked Picked up/)
  await shot('t-10-deliveries')
})

await step('purchasing: supplier and a purchase order received into stock', async () => {
  await go('/purchasing')
  await page.getByRole('tab', { name: 'Suppliers' }).click()
  await page.getByRole('button', { name: 'New supplier' }).click()
  await modal().getByLabel('Name').fill('Hill Roasters')
  await modal().getByRole('button', { name: 'Save' }).click()
  await toast(/Supplier saved/)
  await page.getByRole('tab', { name: 'Purchase orders' }).click()
  await page.getByRole('button', { name: 'New purchase order' }).click()
  await modal().getByLabel('Add an item').fill('LAT-S')
  await modal().getByRole('option', { name: /Small/ }).click()
  await modal().locator('input.qty').first().fill('12')
  await modal().getByRole('button', { name: 'Save draft' }).click()
  await page.waitForURL(/\/purchasing\/[0-9a-f-]{36}$/)
  await page.getByRole('button', { name: 'Mark as ordered' }).click()
  await toast(/Marked as ordered/)
  await page.getByRole('button', { name: 'Receive goods' }).click()
  await modal().getByRole('button', { name: 'Receive' }).click()
  await toast(/received into stock/)
  await shot('t-11-purchase-order')
})

await step('data tables: create a table, a field and a row', async () => {
  await go('/data')
  await page.getByRole('button', { name: 'New table' }).click()
  await modal().getByLabel('Name').fill('Leads')
  await modal().getByRole('button', { name: 'Create' }).click()
  await page.waitForURL(/\/data\/leads/)
  await page.getByRole('button', { name: 'Add field' }).click()
  await modal().getByLabel('Label').fill('Stage')
  await modal().getByLabel('Type').selectOption('select')
  await modal().getByLabel('Options').fill('new\nwon\nlost')
  await modal().getByRole('button', { name: 'Save field' }).click()
  await toast(/Field saved/)
  await shot('t-12-table-fields')
  await page.getByRole('tab', { name: /Rows/ }).click()
  await page.getByRole('button', { name: 'Add row' }).click()
  // required field left empty first: the server's message shows next to the field
  allowed4xx = [['POST', /\/data\/tables\/leads\/rows$/]]
  await modal().getByRole('button', { name: 'Add row' }).click()
  await modal().locator('.err').first().waitFor()
  allowed4xx = []
  await modal().getByLabel('Name').fill('Acme Co')
  await modal().getByLabel('Stage').selectOption('new')
  await modal().getByRole('button', { name: 'Add row' }).click()
  await toast(/Row added/)
  await page.getByRole('cell', { name: 'Acme Co' }).waitFor()
  await shot('t-13-table-rows')
})

await step('imports: CSV into the table (check only, then for real)', async () => {
  await go('/imports?table=leads')
  const csv = Buffer.from('Name,Stage\nBeta Ltd,won\nGamma,lost\n')
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), modal().getByRole('button', { name: 'Choose file' }).click()])
  await chooser.setFiles({ name: 'leads.csv', mimeType: 'text/csv', buffer: csv })
  await modal().getByText('2 rows found').waitFor()
  await shot('t-14-import-mapping')
  await modal().getByRole('button', { name: 'Start import' }).click()
  await toast(/Import started/)
  await page.getByText('Done').first().waitFor({ timeout: 20000 })
  await go('/data/leads')
  await page.getByRole('cell', { name: 'Gamma' }).waitFor()
})

await step('files: upload an image', async () => {
  await go('/files')
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.getByRole('button', { name: 'Upload' }).click()])
  await chooser.setFiles({ name: 'dot.png', mimeType: 'image/png', buffer: png })
  await toast(/File uploaded/)
  await page.getByText('dot.png').click()
  await modal().getByRole('button', { name: /Share link/ }).click()
  await modal().locator('.secret-out code').waitFor()
  await modal().getByRole('button', { name: 'Cancel' }).click()
  await shot('t-15-files')
})

await step('roles: a role with a permission grid, and a team member', async () => {
  await go('/roles')
  await page.getByRole('button', { name: 'New role' }).click()
  await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Barista')
  await page.getByRole('checkbox', { name: 'Orders: read' }).check()
  await page.getByRole('checkbox', { name: 'Orders: create' }).check()
  await page.getByRole('checkbox', { name: 'Leads: read' }).check()
  await page.getByRole('button', { name: 'Save role' }).click()
  await toast(/Role saved/)
  await shot('t-16-roles')
  await go('/team')
  await page.getByRole('button', { name: 'Add person' }).click()
  await modal().getByRole('tab', { name: 'Set a password now' }).click()
  await modal().getByLabel('Email').fill(`bob@${slug}.test`)
  await modal().getByLabel('Password').fill('Another-Strong-Pass-1')
  await modal().getByRole('button', { name: 'Barista' }).click()
  await modal().getByRole('button', { name: 'Add' }).click()
  await toast(/Team member added/)
  await shot('t-17-team')
})

await step('developers: API key shown once, webhook validation', async () => {
  await go('/developers/keys')
  await page.getByRole('button', { name: 'New key' }).click()
  await modal().getByLabel('Name').fill('Website')
  await modal().getByLabel('Role').selectOption({ label: 'Barista' })
  await modal().getByRole('button', { name: 'Create key' }).click()
  await modal().getByText(/^tck_/).waitFor()
  await modal().getByRole('button', { name: 'Close', exact: true }).last().click()
  await page.getByRole('tab', { name: 'Webhooks' }).click()
  await page.getByRole('button', { name: 'Add endpoint' }).click()
  await modal().getByLabel('Address').fill('https://127.0.0.1/hook')
  allowed4xx = [['POST', /\/webhooks$/]]
  await modal().getByRole('button', { name: 'Save' }).click()
  await toast(/public internet|not allowed|private/i)
  allowed4xx = []
  await modal().getByRole('button', { name: 'Cancel' }).click()
  await shot('t-18-developers')
})

for (const [path, heading, n] of [['/customers', 'Customers', '19'], ['/notifications', 'Push notifications', '20'], ['/audit', 'Activity log', '21'], ['/account', 'Your account', '22'], ['/', null, '23']]) {
  await step(`workspace page ${path}`, async () => { await go(path); if (heading) await page.getByRole('heading', { name: heading, exact: true }).waitFor(); await shot(`t-${n}-${path.slice(1) || 'home-after'}`) })
}

await step('a team member only sees what their role allows', async () => {
  await page.getByRole('link', { name: /Workspace admin/ }).click()
  await page.getByRole('heading', { name: 'Your account' }).waitFor()
  await page.locator('main').getByRole('button', { name: 'Sign out' }).click()
  await page.getByLabel('Workspace').fill(slug)
  await page.getByLabel('Email').fill(`bob@${slug}.test`)
  await page.getByLabel('Password').fill('Another-Strong-Pass-1')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL(BASE + '/')
  const nav = page.getByRole('navigation', { name: 'Main' })
  await nav.getByRole('link', { name: 'Orders' }).waitFor()
  if (await nav.getByRole('link', { name: 'Settings' }).count()) throw new Error('Settings link shown to a barista')
  if (await nav.getByRole('link', { name: 'Products' }).count()) throw new Error('Products link shown without products permission')
  await shot('t-24-barista-home')
  await go('/settings/general')
  await page.waitForURL(/\/settings\/modules$/)
  if (await page.getByRole('button', { name: 'Business', exact: true }).count()) throw new Error('Business settings shown to a barista')
  allowed4xx = [['GET', /\/products$/]]
  await go('/products')
  await page.waitForURL(BASE + '/')
  await toast(/Your role does not include that page/)
  allowed4xx = []
})

// Phone width: the same pages must fit without sideways scrolling.
await step('phone layout', async () => {
  await page.setViewportSize({ width: 390, height: 844 })
  for (const p of ['/', '/orders']) {
    await go(p)
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    if (overflow > 1) throw new Error(`${p} scrolls sideways by ${overflow}px on a phone`)
    await shot(`m-${p === '/' ? 'home' : p.slice(1)}`)
  }
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Orders' }).waitFor()
  await page.waitForTimeout(400) // let the menu finish sliding in
  await shot('m-menu')
})

await browser.close()
console.log(`\n${passed.length} steps passed, ${problems.length} problems`)
for (const p of passed) console.log(`✓ ${p}`)
if (problems.length) { console.log('\nProblems:'); for (const p of problems) console.log(`- ${p}`); process.exit(1) }
