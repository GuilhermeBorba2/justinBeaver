const { spawn } = require('node:child_process')
const { existsSync, readFileSync } = require('node:fs')
const path = require('node:path')

function startBackend({ directory, packaged, onExit, spawnProcess = spawn }) {
  const java = packaged
    ? path.join(directory, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
  const jar = path.join(directory, 'justinbeaver.jar')
  if (!existsSync(jar)) throw new Error(`Backend not found: ${jar}. Build the application first (see packaging/README.md).`)
  const env = { ...process.env }
  const configFile = path.join(directory, 'google-client.json')
  if (existsSync(configFile)) {
    const config = JSON.parse(readFileSync(configFile, 'utf8'))
    env.JB_GOOGLE_CLIENT_ID ??= config.clientId
    env.JB_GOOGLE_CLIENT_SECRET ??= config.clientSecret
  }
  const child = spawnProcess(java, [
    '-Xmx1g', '-Dfile.encoding=UTF-8', '-Dserver.address=127.0.0.1', '-Dserver.port=0',
    '-Djustinbeaver.desktop.enabled=true', '-Djustinbeaver.desktop.managed=true', '-jar', jar,
  ], { cwd: directory, env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
  let output = ''
  let exited = false
  const exit = new Promise((resolve) => {
    child.once('error', () => { exited = true; resolve(null) })
    child.once('exit', (code) => {
      exited = true
      resolve(code)
      onExit?.(code)
    })
  })
  let timer
  const ready = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error(`Backend startup timed out.\n${output}`)), 60_000)
    child.once('error', reject)
    child.once('exit', (code) => reject(new Error(`Backend stopped (${code}).\n${output}`)))
    child.stdout.on('data', (chunk) => {
      output = (output + chunk.toString()).slice(-8000)
      const match = output.match(/(?:^|\n)JUSTINBEAVER_READY=(\d+)\r?\n/)
      if (match) resolve(`http://127.0.0.1:${Number(match[1])}`)
    })
    child.stderr.on('data', (chunk) => { output = (output + chunk.toString()).slice(-8000) })
  }).finally(() => clearTimeout(timer))
  child.stdin.on('error', () => {}) // A backend that already exited may have closed its pipe.

  async function stop() {
    if (exited) return
    // EOF asks Spring to close connections and stop cleanly; also works if Electron crashes.
    child.stdin.end()
    const force = setTimeout(() => child.kill('SIGKILL'), 10_000)
    try { await exit } finally { clearTimeout(force) }
  }
  return { ready, stop }
}

module.exports = { startBackend }
