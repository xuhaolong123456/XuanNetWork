import test from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from './markdown.js'
import { isLoginExpired, MAX_FILE_SIZE, validateUpload } from './files.js'

test('renders headings, lists, quotes, fenced code and tables', () => {
  const html = renderMarkdown('# heading\n\n- item\n\n> quote\n\n```js\nconst x = 1\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |')
  for (const tag of ['h1', 'ul', 'blockquote', 'pre', 'table']) assert.ok(html.includes(`<${tag}>`))
  assert.ok(html.includes('heading'))
})

test('escapes raw HTML and code and blocks unsafe links and image requests', () => {
  const html = renderMarkdown('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[x](javascript:alert%281%29)\n\n![external](https://example.com/pixel)\n\n![local](./image.png)\n\n`<iframe>`')
  assert.doesNotMatch(html, /<script|<img|<iframe|href="javascript:/)
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(html.includes('<code>&lt;iframe&gt;</code>'))
  assert.doesNotMatch(renderMarkdown('[local](/api/v1/private)'), /<a /)
  assert.match(renderMarkdown('[site](https://example.com)'), /rel="noopener noreferrer"/)
})

test('direct upload accepts supported extensions and exactly 2 GiB; larger files require chunks', () => {
  for (const name of ['a.MD', 'b.txt', 'c.DOCX', 'd.csv', 'e.XLSX', 'f.pdf', 'g.html', 'h.PPTX']) {
    validateUpload({ name, size: MAX_FILE_SIZE })
  }
  assert.throws(() => validateUpload({ name: 'a.txt', size: MAX_FILE_SIZE + 1 }), /2 GiB/)
  validateUpload({ name: 'a.txt', size: MAX_FILE_SIZE + 1 }, { direct: false })
  for (const name of ['a.exe', 'txt', 'a.md.exe']) {
    assert.throws(() => validateUpload({ name, size: 1 }), /txt/)
  }
  validateUpload({ name: 'empty.txt', size: 0 })
})

test('empty file 401 preserves the session while authentication 401 expires it', () => {
  assert.equal(isLoginExpired({ status: 401, code: 'EMPTY_FILE' }), false)
  assert.equal(isLoginExpired({ status: 401, code: 'UNAUTHORIZED' }), true)
  assert.equal(isLoginExpired({ status: 401 }), true)
  assert.equal(isLoginExpired({ status: 404 }), false)
})
