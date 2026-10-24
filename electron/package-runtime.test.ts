import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
const { pin, digest, verifyRuntime, files } = require('../scripts/windows-runtime.cjs')
let root: string
function fixture() {
  root = mkdtempSync(path.join(tmpdir(), 'runtime-inventory-'))
  for (const file of ['bin/java.exe', 'lib/modules', 'NOTICE', 'legal/java.base/LICENSE']) {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    writeFileSync(path.join(root, file), 'synthetic-runtime-fixture')
  }
  writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify({ version: pin.version, sourceSha256: pin.sha256, modules: pin.modules,
    files: Object.fromEntries(files(root).map((file: string) => [file, digest(path.join(root, file))])) }))
  return root
}
afterEach(() => { if (root) rmSync(root, { recursive: true, force: true }) })
describe('packaging rejects incomplete or modified runtime artifacts', () => {
  it('accepts a complete pinned file inventory', () => expect(verifyRuntime(fixture()).version).toBe(pin.version))
  it('rejects changed executable content before bundling', () => {
    fixture(); writeFileSync(path.join(root, 'bin/java.exe'), 'changed')
    expect(() => verifyRuntime(root)).toThrow('integrity check failed')
  })
  it('rejects missing or unexpected files', () => {
    fixture(); rmSync(path.join(root, 'legal/java.base/LICENSE'))
    expect(() => verifyRuntime(root)).toThrow('inventory differs')
    writeFileSync(path.join(root, 'legal/java.base/LICENSE'), 'synthetic-runtime-fixture')
    writeFileSync(path.join(root, 'extra.dll'), 'unlisted')
    expect(() => verifyRuntime(root)).toThrow('inventory differs')
  })
  it('rejects a manifest from a different upstream archive', () => {
    fixture(); const file = path.join(root, 'runtime-manifest.json')
    const manifest = JSON.parse(readFileSync(file, 'utf8')); manifest.sourceSha256 = 'wrong'
    writeFileSync(file, JSON.stringify(manifest))
    expect(() => verifyRuntime(root)).toThrow('pin differs')
  })
})
