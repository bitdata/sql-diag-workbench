const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const pin = require('./runtime-pin.json');
const root = path.resolve(__dirname, '..');
const image = path.join(root, 'build/windows-runtime');
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function files(dir, prefix = '') {
  return fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en')).flatMap(entry => {
    if (entry.isSymbolicLink()) throw Error('Runtime contains a symbolic link');
    const name = prefix + entry.name;
    return entry.isDirectory() ? files(path.join(dir, entry.name), name + '/') : [name];
  });
}
function verifyRuntime(dir = image) {
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'runtime-manifest.json'), 'utf8'));
  if (manifest.sourceSha256 !== pin.sha256 || manifest.version !== pin.version || JSON.stringify(manifest.modules) !== JSON.stringify(pin.modules)) {
    throw Error('Runtime pin differs from manifest. Run npm run prepare:windows.');
  }
  const actual = files(dir).filter(file => file !== 'runtime-manifest.json');
  if (JSON.stringify(actual) !== JSON.stringify(Object.keys(manifest.files))) throw Error('Runtime file inventory differs from manifest');
  for (const file of actual) if (digest(path.join(dir, file)) !== manifest.files[file]) throw Error(`Runtime integrity check failed: ${file}`);
  for (const required of ['bin/java.exe', 'lib/modules', 'NOTICE']) if (!actual.includes(required)) throw Error(`Missing runtime component: ${required}`);
  if (!actual.some(file => file.startsWith('legal/') && file.endsWith('/LICENSE'))) throw Error('Runtime licenses are missing');
  return manifest;
}
module.exports = { root, image, pin, digest, files, verifyRuntime };
