// Render the existing tracked SVG logo; the old .ico file is a truncated PNG.
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
app.disableHardwareAcceleration();
app.whenReady().then(async () => {
  const svg = fs.readFileSync(path.join(root, 'src/renderer/public/favicon.svg'), 'utf8');
  const window = new BrowserWindow({ width: 256, height: 256, show: false, frame: false, transparent: true,
    webPreferences: { sandbox: true, offscreen: true } });
  await window.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent('<style>body{margin:0}svg{display:block;width:256px;height:256px}</style>' + svg));
  await window.webContents.executeJavaScript('document.fonts.ready');
  const png = (await window.webContents.capturePage()).toPNG();
  const header = Buffer.alloc(22);
  header.writeUInt16LE(1, 2); header.writeUInt16LE(1, 4);
  header.writeUInt16LE(1, 10); header.writeUInt16LE(32, 12);
  header.writeUInt32LE(png.length, 14); header.writeUInt32LE(22, 18);
  fs.writeFileSync(path.join(root, 'build/icon.ico'), Buffer.concat([header, png]));
  app.exit(0);
}).catch(error => { console.error('ICON_PREPARATION_FAILED:', error.message); app.exit(1); });
