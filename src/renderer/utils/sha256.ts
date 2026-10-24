/** Synchronous UTF-8 SHA-256 for snapshot validation, including file:// renderers. */
export function sha256(text: string): string {
  const bytes = new TextEncoder().encode(text)
  const size = Math.ceil((bytes.length + 9) / 64) * 64
  const data = new Uint8Array(size); data.set(bytes); data[bytes.length] = 0x80
  const view = new DataView(data.buffer)
  view.setUint32(size - 8, Math.floor(bytes.length / 0x20000000))
  view.setUint32(size - 4, bytes.length * 8)
  const primes: number[] = []
  for (let n = 2; primes.length < 64; n++) if (!primes.some(p => p * p <= n && n % p === 0)) primes.push(n)
  const h = primes.slice(0, 8).map(p => (Math.sqrt(p) % 1 * 0x100000000) | 0)
  const k = primes.map(p => (Math.cbrt(p) % 1 * 0x100000000) | 0)
  const r = (x: number, n: number) => (x >>> n) | (x << (32 - n))
  for (let offset = 0; offset < size; offset += 64) {
    const w = new Int32Array(64)
    for (let i = 0; i < 16; i++) w[i] = view.getInt32(offset + i * 4)
    for (let i = 16; i < 64; i++) w[i] = (w[i - 16] + (r(w[i - 15], 7) ^ r(w[i - 15], 18) ^ (w[i - 15] >>> 3)) + w[i - 7] + (r(w[i - 2], 17) ^ r(w[i - 2], 19) ^ (w[i - 2] >>> 10))) | 0
    let [a, b, c, d, e, f, g, hh] = h
    for (let i = 0; i < 64; i++) {
      const t = (hh + (r(e, 6) ^ r(e, 11) ^ r(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0
      const u = ((r(a, 2) ^ r(a, 13) ^ r(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0
      hh = g; g = f; f = e; e = (d + t) | 0; d = c; c = b; b = a; a = (t + u) | 0
    }
    const v = [a, b, c, d, e, f, g, hh]
    for (let i = 0; i < 8; i++) h[i] = (h[i] + v[i]) | 0
  }
  return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('')
}
