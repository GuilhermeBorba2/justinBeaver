// Exercises the actual packaged Java backend, including parent-pipe EOF shutdown.
const assert = require('node:assert/strict')
const { mkdtempSync, rmSync, existsSync } = require('node:fs')
const { tmpdir } = require('node:os')
const path = require('node:path')
const { startBackend } = require('./backend.cjs')

async function smoke() {
  const directory = mkdtempSync(path.join(tmpdir(), 'jb-desktop-test-'))
  process.env.JUSTINBEAVER_STORAGE_DIR = directory
  let exitCode
  const backend = startBackend({
    directory: path.join(__dirname, '..', 'packaging', '.work', 'app'), packaged: false,
    onExit: (code) => { exitCode = code },
  })
  try {
    const origin = await backend.ready
    assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/)
    const info = await (await fetch(`${origin}/api/app/info`)).json()
    assert.equal(info.app, 'justinbeaver')
    assert.equal(info.desktop, true)
    assert.equal(existsSync(path.join(directory, 'desktop.port')), false, 'managed launch must not invoke browser handover')
    assert.equal((await fetch(`${origin}/api/google/status`)).status, 200)
    await backend.stop()
    assert.equal(exitCode, 0, 'closing the parent pipe should stop Spring cleanly')
    await assert.rejects(fetch(`${origin}/api/app/info`))
    console.log('PASS: desktop backend starts on a random loopback port and exits with its parent pipe.')
  } finally {
    await backend.stop()
    rmSync(directory, { recursive: true, force: true })
  }
}
smoke().catch((error) => { console.error(error); process.exitCode = 1 })
