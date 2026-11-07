// Fixed upstream JDK, verified before extraction; no dependency on JAVA_HOME/PATH Java.
const fs = require('node:fs');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');
const { root, image, pin, digest, files, verifyRuntime } = require('./windows-runtime.cjs');
async function download(archive) {
  const dir = path.dirname(archive);
  fs.mkdirSync(dir, { recursive: true });
  const results = await Promise.allSettled(Array.from({ length: 16 }, async (_, index) => {
    const start = Math.floor(pin.bytes * index / 16), end = Math.floor(pin.bytes * (index + 1) / 16) - 1;
    const part = path.join(dir, `${pin.archive}.${index}.part`);
    // Only the official asset API. It redirects to the official GitHub asset CDN.
    const code = await new Promise((resolve, reject) => {
      const child = spawn('curl.exe', ['--silent', '--show-error', '--location', '--fail', '--connect-timeout', '15', '--max-time', '300',
        '-H', 'Accept: application/octet-stream', '--range', `${start}-${end}`, '--output', part, pin.assetApi], { windowsHide: true, stdio: 'inherit' });
      child.once('error', reject); child.once('exit', resolve);
    });
    if (code !== 0 || fs.statSync(part).size !== end - start + 1) throw Error(`Runtime archive download failed: part ${index}`);
    return part;
  }));
  const failure = results.find(result => result.status === 'rejected');
  if (failure) throw failure.reason;
  const fd = fs.openSync(archive, 'w');
  try { for (const result of results) fs.writeSync(fd, fs.readFileSync(result.value)); }
  finally { fs.closeSync(fd); }
}
async function main() {
  if (process.platform !== 'win32' || process.arch !== 'x64') {
    console.log(JSON.stringify({ skipped: true, errorReason: 'WINDOWS_X64_RUNTIME_ONLY' })); return;
  }
  const archive = path.resolve(process.env.SQL_WORKBENCH_JDK_ARCHIVE || path.join(root, 'build/runtime-cache', pin.archive));
  if (!fs.existsSync(archive)) await download(archive);
  if (fs.statSync(archive).size !== pin.bytes || digest(archive) !== pin.sha256) throw Error('JDK archive SHA-256/size mismatch; refusing extraction');
  // Unique staging directories avoid overwriting a runtime in use or accepting a partial prior build.
  fs.mkdirSync(path.join(root, 'build'), { recursive: true });
  const stage = fs.mkdtempSync(path.join(root, 'build/runtime-stage-'));
  execFileSync(require('7zip-bin').path7za, ['x', archive, `-o${stage}`, '-y'], { stdio: 'ignore', windowsHide: true });
  const jdk = path.join(stage, 'jdk-' + pin.version);
  const stagedImage = path.join(stage, 'runtime');
  execFileSync(path.join(jdk, 'bin/jlink.exe'), ['--add-modules', pin.modules.join(','), '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', stagedImage], { stdio: 'inherit', windowsHide: true });
  fs.copyFileSync(path.join(jdk, 'NOTICE'), path.join(stagedImage, 'NOTICE'));
  const manifest = { vendor: pin.vendor, version: pin.version, sourceUrl: pin.url, sourceSha256: pin.sha256, modules: pin.modules,
    files: Object.fromEntries(files(stagedImage).map(file => [file, digest(path.join(stagedImage, file))])) };
  fs.writeFileSync(path.join(stagedImage, 'runtime-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  verifyRuntime(stagedImage);
  // Only this fixed generated workspace directory is replaced. Never remove external cache/archive inputs.
  fs.rmSync(image, { recursive: true, force: true });
  fs.renameSync(stagedImage, image);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  execFileSync(require('electron'), [path.join(__dirname, 'prepare-icons.cjs')], { stdio: 'inherit', windowsHide: true, env });
  console.log(JSON.stringify({ status: 'PASS', runtime: image, version: pin.version, sourceSha256: pin.sha256, files: Object.keys(manifest.files).length }));
}
main().catch(error => { console.error(JSON.stringify({ status: 'failed', errorReason: 'WINDOWS_PREPARATION_FAILED', message: error.message })); process.exitCode = 1; });
