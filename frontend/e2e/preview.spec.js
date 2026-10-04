import { test, expect } from '@playwright/test'

const markdownContent = '# 预览验收\n\n正文 **加粗**\n\n| 文件 | 状态 |\n| --- | --- |\n| md | 支持 |\n\n```js\nconsole.log("hello")\n```\n\n<script>window.previewInjected = true</script>'
const textContent = '第一行\n  缩进 <script>window.previewInjected = true</script>'

test.beforeEach(async ({ page }) => {
  // 浏览器用固定接口响应验证真实页面交互，后端权限与磁盘读写由 JUnit 单独覆盖。
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    let data
    if (url.pathname === '/api/v1/auth/me') data = { userId: 7, username: '预览测试' }
    else if (url.pathname === '/api/v1/auth/csrf') data = 'test-csrf'
    else if (url.pathname === '/api/v1/files/file/tree') {
      data = [{ id: 8, parentId: 0, name: '根目录', children: [
        { id: 9, parentId: 8, name: '子目录', children: [] }
      ] }]
    }
    else if (url.pathname === '/api/v1/files/file/download') {
      return route.fulfill({ status: 200, contentType: 'application/octet-stream',
        headers: { 'Content-Disposition': `attachment; filename="download.txt"` }, body: `content-${url.searchParams.get('fileId')}` })
    } else if (url.pathname === '/api/v1/files/files/download') {
      return route.fulfill({ status: 200, contentType: 'application/octet-stream',
        headers: { 'Content-Disposition': 'attachment; filename="files.zip"' }, body: 'zip-content' })
    }
    else if (url.pathname === '/api/v1/files/file/preview') {
      const id = url.searchParams.get('fileId')
      if (id === '404') return route.fulfill({ status: 404, json: { success: false, code: 'FILE_NOT_FOUND', message: '资源不存在' } })
      data = { fileId: id, name: id === '12' ? '说明.md' : '笔记.txt', format: id === '12' ? 'md' : 'txt', content: id === '12' ? markdownContent : textContent }
    } else if (url.pathname === '/api/v1/files/quick-check') {
      data = { exist: false, fileId: null }
    } else if (url.pathname === '/api/v1/files/upload') {
      return route.fulfill({ status: 401, json: { success: false, code: 'EMPTY_FILE', message: '不能上传空文件' } })
    } else if (url.pathname === '/api/v1/files') {
      data = {
        breadcrumbs: [{ id: null, name: '我的文件' }], currentDirectory: null,
        items: [{ id: 12, name: '说明.md', type: 'FILE', sizeBytes: 100 }, { id: 13, name: '笔记.txt', type: 'FILE', sizeBytes: 50 }],
        page: { number: 0, size: 50, totalElements: 2, totalPages: 1 }
      }
    } else return route.fulfill({ status: 404, json: { success: false } })
    return route.fulfill({ json: { success: true, code: 'OK', data } })
  })
})

test('icon opens rendered markdown in the same tab, refresh works and return preserves location', async ({ page, context }) => {
  await page.goto('/drive?parentId=8&page=0')
  await page.locator('.file-row').filter({ hasText: '说明.md' }).locator('.file-icon').click()
  await expect(page).toHaveURL(/\/drive\/preview\/12\?parentId=8&page=0/)
  await expect(page.locator('.markdown-content h1')).toHaveText('预览验收')
  expect(await page.locator('.preview-page').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(500)
  await expect(page.locator('.markdown-content table')).toBeVisible()
  await expect(page.locator('.markdown-content script')).toHaveCount(0)
  expect(await page.evaluate(() => window.previewInjected)).toBeUndefined()
  expect(context.pages()).toHaveLength(1)
  await page.reload()
  await expect(page.locator('.markdown-content h1')).toHaveText('预览验收')
  await page.screenshot({ path: 'test-results/markdown-preview.png', fullPage: true })
  await page.getByRole('button', { name: '返回文件列表' }).click()
  await expect(page).toHaveURL(/\/drive\?parentId=8&page=0/)
})

test('checkbox and delete action do not preview, while row background opens escaped text', async ({ page }) => {
  await page.goto('/drive')
  const row = page.locator('.file-row').filter({ hasText: '笔记.txt' })
  await row.getByRole('checkbox').check()
  await expect(page).toHaveURL(/\/drive$/)
  await row.getByRole('button', { name: '删除 笔记.txt' }).click()
  await expect(page).toHaveURL(/\/drive$/)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  // 文件行左侧内边距不属于图标或名称按钮，用于覆盖整个文件框的点击行为。
  await row.click({ position: { x: 1, y: 1 } })
  await expect(page).toHaveURL(/\/drive\/preview\/13/)
  expect(await page.locator('.text-content').textContent()).toBe(textContent)
  await expect(page.locator('.text-content script')).toHaveCount(0)
})

test('404 shows an error without partial content and supports retry', async ({ page }) => {
  await page.goto('/drive/preview/404')
  await expect(page.getByRole('heading', { name: '资源不存在' })).toBeVisible()
  await expect(page.locator('.markdown-content, .text-content')).toHaveCount(0)
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByRole('heading', { name: '资源不存在' })).toBeVisible()
})

test('empty file upload reports 401 business error without logging the user out', async ({ page }) => {
  await page.goto('/drive')
  await page.locator('input[type=file]').setInputFiles({ name: 'empty.txt', mimeType: 'text/plain', buffer: Buffer.alloc(0) })
  await expect(page.getByText('empty.txt 上传失败：不能上传空文件')).toBeVisible()
  await expect(page).toHaveURL(/\/drive$/)
  expect(await page.evaluate(() => localStorage.getItem('current_user'))).not.toBeNull()
})

test('single download works from list and preview while batch reuses checkboxes', async ({ page }) => {
  const requests = []
  page.on('request', request => {
    if (request.url().includes('/api/v1/files/file/download?')) requests.push(new URL(request.url()).searchParams)
    if (request.url().endsWith('/api/v1/files/files/download')) requests.push(JSON.parse(request.postData()))
  })
  await page.goto('/drive')
  const first = page.locator('.file-row').filter({ hasText: '说明.md' })
  const second = page.locator('.file-row').filter({ hasText: '笔记.txt' })
  const single = page.waitForEvent('download')
  await first.getByRole('button', { name: '下载 说明.md' }).click()
  expect((await single).suggestedFilename()).toBe('说明.md')
  await first.getByRole('checkbox').check()
  await second.getByRole('checkbox').check()
  const batch = page.waitForEvent('download')
  await page.getByRole('button', { name: '批量下载' }).click()
  expect((await batch).suggestedFilename()).toBe('files.zip')
  await expect(page.getByText('已开始下载 2 个文件的 ZIP 压缩包')).toBeVisible()
  await first.locator('.file-entry').click()
  const preview = page.waitForEvent('download')
  await page.getByRole('button', { name: '↓ 下载' }).click()
  expect((await preview).suggestedFilename()).toBe('说明.md')
  expect(requests.map(query => query instanceof URLSearchParams
    ? [query.get('filename'), query.get('fileId')] : query))
    .toEqual([['说明.md', '12'], { ids: [12, 13] }, ['说明.md', '12']])
})

test('folder tree button opens a graph page showing parent and child relationships', async ({ page }) => {
  await page.goto('/drive')
  await page.getByRole('button', { name: /文件夹树/ }).click()
  await expect(page).toHaveURL(/\/drive\/tree$/)
  await expect(page.getByRole('heading', { name: '文件夹关系图' })).toBeVisible()
  await expect(page.locator('.topology-node')).toHaveCount(3)
  await expect(page.locator('.topology-edge[data-from="root"][data-to="8"]')).toHaveCount(1)
  await expect(page.locator('.topology-edge[data-from="8"][data-to="9"]')).toHaveCount(1)
  await page.getByRole('button', { name: '放大' }).click()
  await expect(page.locator('.topology-svg > g')).not.toHaveAttribute('transform', 'translate(0 0) scale(1)')
  await page.getByRole('button', { name: '适应画布' }).click()
  await expect(page.locator('.topology-svg > g')).toHaveAttribute('transform', 'translate(0 0) scale(1)')
  await page.getByRole('searchbox', { name: '查找文件夹' }).fill('子目录')
  await expect(page.locator('.topology-node.faded')).toHaveCount(1)
  await page.getByRole('button', { name: '选择文件夹 子目录' }).click()
  await expect(page.locator('.topology-detail p')).toHaveText('我的文件 / 根目录 / 子目录')
  await page.getByRole('button', { name: '进入目录' }).click()
  await expect(page).toHaveURL(/\/drive\?parentId=9/)
})

test('empty folder graph shows no files after 204', async ({ page }) => {
  await page.route('**/api/v1/files/file/tree', route => route.fulfill({ status: 204, body: '' }))
  await page.goto('/drive')
  await page.getByRole('button', { name: /文件夹树/ }).click()
  await expect(page).toHaveURL(/\/drive\/tree$/)
  await expect(page.getByRole('heading', { name: '无文件' })).toBeVisible()
})

test('file selection computes full MD5 in worker and instant hit performs no multipart transfer', async ({ page }) => {
  const uploads = []
  await page.route('**/api/v1/files/quick-check', async route => {
    expect(route.request().postDataJSON().fileHash).toBe('5d41402abc4b2a76b9719d911017c592')
    await route.fulfill({ json: { success: true, data: { exist: true, fileId: 99 } } })
  })
  page.on('request', request => {
    if (/\/files\/(upload|file\/chunk-upload)$/.test(new URL(request.url()).pathname)) uploads.push(request.url())
  })
  await page.goto('/drive')
  await page.locator('input[type=file]').setInputFiles({ name: 'notes.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') })
  await expect(page.getByText('上传成功：1 个文件已秒传')).toBeVisible()
  expect(uploads).toEqual([])
  await page.screenshot({ path: 'test-results/upload-md5.png', fullPage: true })
})

test('upload authentication failure stops the batch and returns to login', async ({ page }) => {
  let expired = false
  let checks = 0
  await page.route('**/api/v1/auth/me', route => route.fulfill({ status: expired ? 401 : 200,
    json: expired ? { success: false, code: 'UNAUTHORIZED' } : { success: true, data: { userId: 7, username: '上传测试' } } }))
  await page.route('**/api/v1/files/quick-check', route => {
    expired = true
    checks++
    return route.fulfill({ status: 401, json: { success: false, code: 'UNAUTHORIZED', message: '用户未登录，请重新登录' } })
  })
  await page.goto('/drive')
  await page.locator('input[type=file]').setInputFiles([
    { name: 'first.txt', mimeType: 'text/plain', buffer: Buffer.from('first') },
    { name: 'second.txt', mimeType: 'text/plain', buffer: Buffer.from('second') }
  ])
  await expect(page).toHaveURL(/\/login$/)
  expect(checks).toBe(1)
})

test('folder graph shows unavailable state when access is denied', async ({ page }) => {
  await page.route('**/api/v1/files/file/tree', route => route.fulfill({
    status: 404, json: { success: false, code: 'FILE_NOT_FOUND', message: '资源不存在' }
  }))
  await page.goto('/drive/tree')
  await expect(page.getByRole('heading', { name: '资源不存在' })).toBeVisible()
  await expect(page.locator('.topology-node')).toHaveCount(0)
})
