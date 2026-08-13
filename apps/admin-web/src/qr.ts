/** Byte-mode QR, ECC L, versions 1–10. Encodes Native code_url for the iPad desk. */
const BYTE_CAP = [0, 17, 32, 53, 78, 106, 134]
const DATA_CW = [0, 19, 34, 55, 80, 108, 136]
const EC_L = [0, 7, 10, 15, 20, 26, 36]
const ALIGN: Record<number, number[]> = {
  2: [6, 18],
  3: [6, 22],
  4: [6, 26],
  5: [6, 30],
  6: [6, 34],
  7: [6, 22, 38],
  8: [6, 24, 42],
  9: [6, 26, 46],
  10: [6, 28, 50],
}

function gfTables() {
  const exp = new Array<number>(512)
  const log = new Array<number>(256)
  let x = 1
  for (let i = 0; i < 255; i++) {
    exp[i] = x
    log[x] = i
    x <<= 1
    if (x & 0x100) {
      x ^= 0x11d
    }
  }
  for (let i = 255; i < 512; i++) {
    exp[i] = exp[i - 255]
  }
  return { exp, log }
}

const GF = gfTables()

function mul(a: number, b: number) {
  if (a === 0 || b === 0) {
    return 0
  }
  return GF.exp[GF.log[a] + GF.log[b]]
}

function rsGen(ec: number) {
  let poly = [1]
  for (let i = 0; i < ec; i++) {
    const next = new Array(poly.length + 1).fill(0)
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j]
      next[j + 1] ^= mul(poly[j], GF.exp[i])
    }
    poly = next
  }
  return poly
}

function rsEcc(data: number[], ec: number) {
  const gen = rsGen(ec)
  const res = new Array(ec).fill(0)
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ res[0]
    res.shift()
    res.push(0)
    if (factor === 0) {
      continue
    }
    for (let j = 0; j < ec; j++) {
      res[j] ^= mul(gen[j + 1] || 0, factor)
    }
  }
  return res
}

function encodeData(text: string, version: number) {
  const bytes = Array.from(text).map((c) => c.charCodeAt(0) & 0xff)
  const cap = DATA_CW[version]
  const ec = EC_L[version]
  const bits: number[] = []
  const push = (val: number, n: number) => {
    for (let i = n - 1; i >= 0; i--) {
      bits.push((val >> i) & 1)
    }
  }
  push(0b0100, 4)
  push(bytes.length, version < 10 ? 8 : 16)
  bytes.forEach((b) => push(b, 8))
  const maxBits = cap * 8
  const remain = Math.min(4, maxBits - bits.length)
  for (let i = 0; i < remain; i++) {
    bits.push(0)
  }
  while (bits.length % 8 !== 0) {
    bits.push(0)
  }
  const data: number[] = []
  for (let i = 0; i < bits.length; i += 8) {
    let v = 0
    for (let j = 0; j < 8 && i + j < bits.length; j++) {
      v = (v << 1) | bits[i + j]
    }
    if (bits.length - i < 8) {
      v <<= 8 - (bits.length - i)
    }
    data.push(v)
  }
  const pad = [0xec, 0x11]
  let p = 0
  while (data.length < cap) {
    data.push(pad[p % 2])
    p++
  }
  return data.concat(rsEcc(data, ec))
}

function sizeOf(version: number) {
  return 17 + 4 * version
}

function maskFn(id: number, x: number, y: number) {
  switch (id) {
    case 0:
      return (x + y) % 2 === 0
    case 1:
      return y % 2 === 0
    case 2:
      return x % 3 === 0
    case 3:
      return (x + y) % 3 === 0
    case 4:
      return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0
    default:
      return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
  }
}

function bch(data: number) {
  let d = data << 10
  const g = 0x537
  for (let i = 14; i >= 10; i--) {
    if ((d >> i) & 1) {
      d ^= g << (i - 10)
    }
  }
  return ((data << 10) | d) ^ 0x5412
}

export function qrModules(text: string): boolean[][] {
  if (!text) {
    return []
  }
  let version = 1
  while (version <= 6 && text.length > BYTE_CAP[version]) {
    version++
  }
  if (version > 6) {
    return []
  }
  let best: boolean[][] | null = null
  let bestScore = Number.MAX_SAFE_INTEGER
  for (let mask = 0; mask < 8; mask++) {
    const n = sizeOf(version)
    const reserved = Array.from({ length: n }, () => new Array<number>(n).fill(0))
    const mark = (x: number, y: number) => {
      if (x >= 0 && y >= 0 && x < n && y < n) {
        reserved[y][x] = 2
      }
    }
    const finderR = (x: number, y: number) => {
      for (let dy = -1; dy <= 7; dy++) {
        for (let dx = -1; dx <= 7; dx++) {
          mark(x + dx, y + dy)
        }
      }
    }
    finderR(0, 0)
    finderR(n - 7, 0)
    finderR(0, n - 7)
    for (let i = 8; i < n - 8; i++) {
      mark(i, 6)
      mark(6, i)
    }
    for (let i = 0; i < 9; i++) {
      mark(i, 8)
      mark(8, i)
    }
    for (let i = 0; i < 8; i++) {
      mark(n - 1 - i, 8)
      mark(8, n - 1 - i)
    }
    mark(8, n - 8)
    const mod = Array.from({ length: n }, () => new Array<boolean>(n).fill(false))
    const finder = (x: number, y: number) => {
      for (let dy = 0; dy < 7; dy++) {
        for (let dx = 0; dx < 7; dx++) {
          mod[y + dy][x + dx] =
            dx === 0 || dx === 6 || dy === 0 || dy === 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4)
        }
      }
    }
    finder(0, 0)
    finder(n - 7, 0)
    finder(0, n - 7)
    for (let i = 8; i < n - 8; i++) {
      mod[6][i] = i % 2 === 0
      mod[i][6] = i % 2 === 0
    }
    const pts = ALIGN[version]
    if (pts) {
      pts.forEach((y) => {
        pts.forEach((x) => {
          if ((x === 6 && y === 6) || (x === 6 && y === pts[pts.length - 1]) || (y === 6 && x === pts[pts.length - 1])) {
            return
          }
          for (let dy = -2; dy <= 2; dy++) {
            for (let dx = -2; dx <= 2; dx++) {
              mod[y + dy][x + dx] = Math.max(Math.abs(dx), Math.abs(dy)) !== 1
            }
          }
        })
      })
    }
    const data = encodeData(text, version)
    let bit = 0
    const total = data.length * 8
    const get = () => {
      if (bit >= total) {
        return 0
      }
      const v = (data[bit >> 3] >> (7 - (bit & 7))) & 1
      bit++
      return v
    }
    let up = true
    for (let x = n - 1; x > 0; x -= 2) {
      if (x === 6) {
        x--
      }
      for (let i = 0; i < n; i++) {
        const y = up ? n - 1 - i : i
        for (let dx = 0; dx < 2; dx++) {
          const xx = x - dx
          if (reserved[y][xx]) {
            continue
          }
          let v = get() === 1
          if (maskFn(mask, xx, y)) {
            v = !v
          }
          mod[y][xx] = v
        }
      }
      up = !up
    }
    const bits = bch((0b01 << 3) | mask)
    for (let i = 0; i < 15; i++) {
      const on = ((bits >> i) & 1) === 1
      if (i < 6) {
        mod[i][8] = on
        mod[8][n - 1 - i] = on
      } else if (i < 8) {
        mod[i + 1][8] = on
        mod[8][n - 1 - i] = on
      } else {
        mod[8][14 - i] = on
        mod[n - 15 + i][8] = on
      }
    }
    mod[n - 8][8] = true
    let penalty = 0
    for (let y = 0; y < n; y++) {
      let run = 1
      for (let x = 1; x < n; x++) {
        if (mod[y][x] === mod[y][x - 1]) {
          run++
        } else {
          if (run >= 5) {
            penalty += run - 2
          }
          run = 1
        }
      }
      if (run >= 5) {
        penalty += run - 2
      }
    }
    if (penalty < bestScore) {
      bestScore = penalty
      best = mod
    }
  }
  return best ?? []
}

export function qrSvg(text: string, size = 168) {
  const mod = qrModules(text)
  if (!mod.length) {
    return ''
  }
  const n = mod.length
  const cell = size / (n + 2)
  let rects = ''
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (mod[y][x]) {
        rects += `<rect x="${((x + 1) * cell).toFixed(2)}" y="${((y + 1) * cell).toFixed(2)}" width="${cell.toFixed(2)}" height="${cell.toFixed(2)}"/>`
      }
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><g fill="#14352c">${rects}</g></svg>`
}
