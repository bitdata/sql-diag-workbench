import { app, BrowserWindow, ipcMain, dialog, shell } from 'electron'
import { join, resolve } from 'path'
import { mkdirSync } from 'fs'
import { BackendLauncher } from '../backend-launcher'
import { ExitCoordinator, type ExitDecision } from '../workspace-exit'

// 禁用 GPU 硬件加速以解决某些环境下的黑屏问题
app.disableHardwareAcceleration()

// Explicit opt-in for the desktop harness; never use the user's profile/database.
const testRoot = process.env['SQL_WORKBENCH_TEST_ROOT']
if (testRoot) {
  const profile = join(resolve(testRoot), 'profile')
  mkdirSync(profile, { recursive: true })
  app.setPath('userData', profile)
}

const isDev = !app.isPackaged

let mainWindow: BrowserWindow | null = null
let splashWindow: BrowserWindow | null = null
const backendLauncher = new BackendLauncher()
// Initial failures must remain readable after the window opens.
ipcMain.handle('get-backend-url', () => ({
  url: `http://127.0.0.1:${backendLauncher.getPort()}`,
  token: backendLauncher.getToken(),
}))
ipcMain.handle('get-backend-status', () => backendLauncher.getHealthStatus())
const exit = new ExitCoordinator({
  request: attemptId => {
    if (!mainWindow || mainWindow.isDestroyed() || mainWindow.webContents.isCrashed()) throw new Error('Workspace window unavailable')
    mainWindow.webContents.send('workspace-exit-request', { attemptId })
  },
  cancel: attemptId => {
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send('workspace-exit-request', { attemptId, cancelled: true })
  },
  unavailable: attemptId => {
    void dialog.showMessageBox({ type: 'warning', title: 'Could not confirm workspace saving',
      message: 'The workspace window did not confirm that your changes were saved.',
      detail: 'Cancel exit to keep working, try again, or exit without confirming saved changes.',
      buttons: ['Cancel exit', 'Retry', 'Exit without confirming saved changes'], defaultId: 0, cancelId: 0,
    }).then(({ response }) => exit.decision(attemptId, response === 1 ? 'retry' : response === 2 ? 'discard' : 'cancel'))
      .catch(() => exit.decision(attemptId, 'cancel'))
  },
  quit: () => app.quit(),
})
ipcMain.on('workspace-exit-result', (event, result: unknown) => {
  if (event.sender === mainWindow?.webContents) exit.result(result)
})
ipcMain.on('workspace-exit-decision', (event, value: { attemptId?: unknown; decision?: unknown }) => {
  if (event.sender !== mainWindow?.webContents || !value || typeof value.attemptId !== 'string'
    || !['retry', 'cancel', 'discard'].includes(String(value.decision))) return
  exit.decision(value.attemptId, value.decision as ExitDecision)
})

function createSplashWindow(): BrowserWindow {
  splashWindow = new BrowserWindow({
    width: 400,
    height: 300,
    frame: false,
    center: true,
    alwaysOnTop: true,
    resizable: false,
    show: false,
    backgroundColor: '#1a1a1a',
    webPreferences: {
      sandbox: true
    }
  })

  const splashHtml = `
    <html>
      <head>
        <style>
          body { margin: 0; background: #1a1a1a; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: Arial, sans-serif; }
          h1 { color: #ffffff; font-size: 18px; margin-bottom: 30px; }
          .progress-container { width: 200px; height: 4px; background: #333; border-radius: 2px; overflow: hidden; }
          .progress-bar { width: 50%; height: 100%; background: #4a9eff; animation: loading 1.5s ease-in-out infinite; }
          @keyframes loading { 0% { width: 0%; margin-left: 0; } 50% { width: 50%; margin-left: 25%; } 100% { width: 0%; margin-left: 100%; } }
        </style>
      </head>
      <body>
        <h1>SQL Diagnostic Workbench</h1>
        <div class="progress-container"><div class="progress-bar"></div></div>
      </body>
    </html>
  `
  splashWindow.loadURL(`data:text/html,${splashHtml}`)
  return splashWindow
}

function closeSplashWindow(): void {
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.close()
    splashWindow = null
  }
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 680,
    title: 'SQL Diagnostic Workbench',
    backgroundColor: '#1a1a1a',
    show: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  mainWindow.on('ready-to-show', () => {
    mainWindow?.show()
  })
  mainWindow.on('close', event => {
    if (exit.committed) return
    event.preventDefault()
    exit.request()
  })
  mainWindow.webContents.on('render-process-gone', () => exit.unavailable())
  mainWindow.on('unresponsive', () => exit.unavailable())

  mainWindow.webContents.setWindowOpenHandler((details) => {
    shell.openExternal(details.url)
    return { action: 'deny' }
  })

  if (isDev && process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }
}

async function startBackend(): Promise<void> {
  // Forward backend status changes to renderer
  backendLauncher.on('status-change', (event) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('backend-status-change', event)
    }
  })

  await backendLauncher.start()
}

app.whenReady().then(async () => {
  const splash = createSplashWindow()
  splash.show()

  try {
    await startBackend()
  } catch {
    // Launcher retains a safe structured failure for the renderer's first IPC read.
    console.error('Backend startup failed:', backendLauncher.getStatus().errorReason)
  }

  // Close splash once backend is running
  closeSplashWindow()
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('before-quit', event => {
  if (exit.committed || !mainWindow) return
  event.preventDefault()
  exit.request()
})
app.on('will-quit', () => backendLauncher?.stop())
