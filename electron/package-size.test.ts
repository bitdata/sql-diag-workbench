import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { afterEach, describe, expect, it } from 'vitest'
const { trimWindowsSqlite } = require('../scripts/after-pack.cjs')
const sevenZip = require('7zip-bin').path7za
let work: string
function zip(args: string[], cwd = work): string {
  return execFileSync(sevenZip, args, { cwd, encoding: 'utf8', windowsHide: true })
}
function fixture(includeWindows = true) {
  work = mkdtempSync(path.join(tmpdir(), 'sqlite-package-test-'))
  const payload = path.join(work, 'payload')
  const keep = 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll'
  const contents = {
    'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\n',
    'META-INF/LICENSE': 'license retained',
    'org/sqlite/JDBC.class': 'unchanged class bytes',
    'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so': 'linux native',
    ...(includeWindows ? { [keep]: 'windows native' } : {}),
  }
  for (const [name, value] of Object.entries(contents)) {
    mkdirSync(path.dirname(path.join(payload, name)), { recursive: true })
    writeFileSync(path.join(payload, name), value)
  }
  const library = path.join(work, 'BOOT-INF/lib/sqlite-jdbc-test.jar')
  mkdirSync(path.dirname(library), { recursive: true })
  zip(['a', '-tzip', library, '.'], payload)
  writeFileSync(path.join(work, 'BOOT-INF/lib/other.jar'), 'other dependency unchanged')
  const jar = path.join(work, 'backend.jar')
  zip(['a', '-tzip', jar, 'BOOT-INF', '-mx=0'])
  return { jar, library, keep }
}
afterEach(() => { if (work) rmSync(work, { recursive: true, force: true }) })
describe('Windows SQLite packaging', () => {
  it('preserves classes, licenses and other dependencies and stores the nested JAR', () => {
    const { jar, library, keep } = fixture()
    const result = trimWindowsSqlite(jar)
    expect(result.removedNativeLibraries).toBe(1)
    zip(['x', jar, 'BOOT-INF/lib/sqlite-jdbc-test.jar', `-o${work}`, '-y'])
    expect(zip(['e', library, keep, '-so'])).toBe('windows native')
    expect(zip(['e', library, 'META-INF/LICENSE', '-so'])).toBe('license retained')
    expect(zip(['e', library, 'org/sqlite/JDBC.class', '-so'])).toBe('unchanged class bytes')
    expect(zip(['l', '-slt', library])).not.toContain('libsqlitejdbc.so')
    expect(zip(['e', jar, 'BOOT-INF/lib/other.jar', '-so'])).toBe('other dependency unchanged')
    expect(zip(['l', '-slt', jar, 'BOOT-INF/lib/sqlite-jdbc-test.jar'])).toContain('Method = Store')
  })
  it('fails without modifying the backend if the target native library is missing', () => {
    const { jar } = fixture(false)
    const before = readFileSync(jar)
    expect(() => trimWindowsSqlite(jar)).toThrow('Windows x64 native library is missing')
    expect(readFileSync(jar)).toEqual(before)
  })
})
