const { app, BrowserWindow, dialog, ipcMain, Menu, shell } = require('electron')
const path = require('node:path')
const { startBackend } = require('./backend.cjs')
const { isGoogleLogin, isAppPage } = require('./policy.cjs')

app.setName('JustinBeaver')
app.setAppUserModelId('io.justinbeaver.desktop')
let mainWindow
let splash
let backend
let origin
let quitting = false

function focusWindow() {
  if (!mainWindow) return
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', focusWindow)
  app.on('activate', focusWindow)
  app.on('window-all-closed', () => app.quit())
  app.on('before-quit', (event) => {
    if (!backend || quitting) return
    event.preventDefault()
    quitting = true
    void backend.stop().finally(() => app.exit())
  })

  app.whenReady().then(async () => {
    Menu.setApplicationMenu(null)
    splash = new BrowserWindow({
      width: 560, height: 320, frame: false, resizable: false, movable: true, show: false, skipTaskbar: true,
      alwaysOnTop: true, backgroundColor: '#2a2423', icon: path.join(__dirname, 'icon.png'),
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true },
    })
    splash.once('ready-to-show', () => splash && splash.show())
    splash.on('closed', () => { splash = null })
    void splash.loadFile(path.join(__dirname, 'splash.html'))
    backend = startBackend({
      directory: app.isPackaged ? path.join(process.resourcesPath, 'backend') : path.join(__dirname, '..', 'packaging', '.work', 'app'),
      packaged: app.isPackaged,
      onExit: (code) => {
        if (!quitting && mainWindow) {
          if (code !== 0) dialog.showErrorBox('JustinBeaver stopped', 'The database service stopped unexpectedly. Reopen JustinBeaver to try again.')
          app.quit()
        }
      },
    })
    origin = await backend.ready
    mainWindow = new BrowserWindow({
      title: 'JustinBeaver', width: 1440, height: 960, minWidth: 900, minHeight: 600,
      show: false, backgroundColor: '#232120',
      icon: path.join(__dirname, 'icon.png'),
      webPreferences: {
        preload: path.join(__dirname, 'preload.cjs'),
        nodeIntegration: false, contextIsolation: true, sandbox: true,
      },
    })
    const contents = mainWindow.webContents
    contents.session.setPermissionRequestHandler((_wc, _permission, callback) => callback(false))
    contents.session.setPermissionCheckHandler(() => false)
    contents.on('will-attach-webview', (event) => event.preventDefault())
    contents.on('will-navigate', (event, url) => {
      if (!isAppPage(url, origin)) event.preventDefault()
    })
    contents.setWindowOpenHandler(({ url }) => {
      if (isGoogleLogin(url)) void shell.openExternal(url).catch(() => {})
      return { action: 'deny' }
    })
    ipcMain.handle('google-sign-in', async (event) => {
      if (event.sender !== contents || event.senderFrame !== contents.mainFrame || !isAppPage(event.senderFrame.url, origin)) {
        throw new Error('Sign-in must be started from JustinBeaver.')
      }
      const response = await fetch(`${origin}/api/google/login`, { method: 'POST', headers: { 'X-JB-Client': 'desktop' } })
      const body = await response.json()
      if (!response.ok) throw new Error(body.detail || 'Google sign-in could not be started.')
      if (!isGoogleLogin(body.url)) throw new Error('Invalid Google sign-in address.')
      await shell.openExternal(body.url)
      return { url: body.url }
    })
    mainWindow.once('ready-to-show', () => {
      if (splash) splash.close()
      mainWindow.show()
      console.log('JustinBeaver desktop window ready')
    })
    await mainWindow.loadURL(origin)
  }).catch((error) => {
    if (splash) splash.close()
    dialog.showErrorBox('Could not open JustinBeaver', error.message)
    app.quit()
  })
}
