const fs = require('node:fs');
const path = require('node:path');
const { isBuiltin } = require('node:module');
const { root, verifyRuntime } = require('./windows-runtime.cjs');
module.exports = async context => {
  // node_modules is excluded: fail the build if main/preload gain an external dependency.
  for (const entry of ['out/main/index.js', 'out/preload/index.js']) {
    const code = fs.readFileSync(path.join(root, entry), 'utf8');
    for (const match of code.matchAll(/\brequire\(["']([^"']+)["']\)/g)) {
      if (match[1] !== 'electron' && !isBuiltin(match[1])) {
        throw Error(`Unbundled runtime dependency ${match[1]} in ${entry}; update the packaging dependency policy`);
      }
    }
  }
  if (context.electronPlatformName !== 'win32') return;
  if (context.arch !== 1) throw Error('Windows runtime supports x64 only');
  verifyRuntime();
  const icon = fs.readFileSync(path.join(root, 'build/icon.ico'));
  if (icon.readUInt16LE(0) !== 0 || icon.readUInt16LE(2) !== 1) throw Error('Generated Windows icon is missing or invalid');
  if (!fs.existsSync(path.join(root, 'backend/target/backend.jar'))) throw Error('Build backend/target/backend.jar with Maven before packaging');
};
