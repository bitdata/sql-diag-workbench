const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const sevenZip = require('7zip-bin').path7za;

function archive(args, cwd) {
  return execFileSync(sevenZip, args, { cwd, windowsHide: true, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
}
function entries(file) {
  return [...archive(['l', '-slt', file]).matchAll(/^Path = (.+)\r?$/gm)]
    .map(match => match[1].trim().replace(/\\/g, '/')).slice(1);
}

// Only the packaged copy is edited. Keep the Maven JAR usable on every platform.
function trimWindowsSqlite(jar) {
  const libraries = entries(jar).filter(name => /^BOOT-INF\/lib\/sqlite-jdbc-[^/]+\.jar$/.test(name));
  if (libraries.length !== 1) throw Error('Expected exactly one SQLite JDBC library in the backend JAR');
  const library = libraries[0];
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'sql-workbench-package-'));
  try {
    const candidate = path.join(work, 'backend.jar');
    fs.copyFileSync(jar, candidate);
    archive(['x', candidate, library, `-o${work}`, '-y']);
    const sqlite = path.join(work, library);
    const keep = 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll';
    const nativeFiles = entries(sqlite).filter(name => name.startsWith('org/sqlite/native/') && /\.(dll|so|dylib|jnilib)$/.test(name));
    if (!nativeFiles.includes(keep)) throw Error('SQLite Windows x64 native library is missing');
    const removed = nativeFiles.filter(name => name !== keep);
    if (removed.length) archive(['d', sqlite, ...removed]);
    const remaining = entries(sqlite).filter(name => name.startsWith('org/sqlite/native/') && /\.(dll|so|dylib|jnilib)$/.test(name));
    if (remaining.length !== 1 || remaining[0] !== keep) throw Error('SQLite native library inventory differs from Windows x64');
    // Spring Boot requires nested JARs to be STORED, not deflated.
    archive(['u', '-tzip', candidate, library, '-mx=0'], work);
    archive(['t', candidate]);
    const beforeBytes = fs.statSync(jar).size;
    const afterBytes = fs.statSync(candidate).size;
    fs.copyFileSync(candidate, jar);
    return { status: 'PASS', platform: 'win32-x64', removedNativeLibraries: removed.length, beforeBytes, afterBytes };
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

module.exports = async context => {
  if (context.electronPlatformName !== 'win32') return;
  if (context.arch !== 1) throw Error('SQLite Windows packaging supports x64 only');
  console.log(JSON.stringify(trimWindowsSqlite(path.join(context.appOutDir, 'resources', 'backend.jar'))));
};
module.exports.trimWindowsSqlite = trimWindowsSqlite;
