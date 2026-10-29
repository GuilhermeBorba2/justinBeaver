const { test } = require('node:test')
const assert = require('node:assert/strict')
const { isGoogleLogin, isAppPage } = require('./policy.cjs')

test('only the Google authorization endpoint may open in the system browser', () => {
  assert.equal(isGoogleLogin('https://accounts.google.com/o/oauth2/v2/auth?client_id=example&state=abc'), true)
  for (const url of [
    'https://accounts.google.com.evil.test/o/oauth2/v2/auth',
    'https://accounts.google.com@evil.test/o/oauth2/v2/auth',
    'https://evil.test@accounts.google.com/o/oauth2/v2/auth',
    'https://accounts.google.com:444/o/oauth2/v2/auth',
    'http://accounts.google.com/o/oauth2/v2/auth',
    'https://accounts.google.com/other', 'file:///tmp/a', 'javascript:alert(1)', '',
  ]) assert.equal(isGoogleLogin(url), false, url)
})

test('the desktop window stays on its own backend origin and port', () => {
  const origin = 'http://127.0.0.1:34567'
  assert.equal(isAppPage(`${origin}/?x=1`, origin), true)
  for (const url of ['http://127.0.0.1:34568/', 'http://localhost:34567/', 'https://evil.test', 'about:blank']) {
    assert.equal(isAppPage(url, origin), false, url)
  }
})
