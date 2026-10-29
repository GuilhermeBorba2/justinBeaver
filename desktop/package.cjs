const { packager } = require('@electron/packager')
const path = require('node:path')

const [platform, version] = process.argv.slice(2)
if (!['linux', 'win32'].includes(platform) || !/^\d+\.\d+\.\d+$/.test(version || '')) {
  throw new Error('Usage: node desktop/package.cjs linux|win32 x.y.z')
}
packager({
  dir: __dirname, name: 'justinbeaver', executableName: 'justinbeaver', appVersion: version,
  platform, arch: 'x64', asar: true, overwrite: true,
  electronVersion: require('electron/package.json').version,
  out: path.join(__dirname, '..', 'packaging', '.work', 'electron'),
  extraResource: [path.join(__dirname, '..', 'packaging', '.work', 'backend')],
  icon: path.join(__dirname, '..', 'packaging', 'assets', 'justinbeaver.ico'),
  ignore: [/\.test\.cjs$/, /package\.cjs$/, /smoke\.cjs$/, /package-lock\.json$/],
  win32metadata: { CompanyName: 'JustinBeaver', ProductName: 'JustinBeaver', FileDescription: 'JustinBeaver database client' },
}).catch((error) => { console.error(error); process.exitCode = 1 })
