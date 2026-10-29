const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('justinBeaverDesktop', {
  openGoogleSignIn: () => ipcRenderer.invoke('google-sign-in'),
})
