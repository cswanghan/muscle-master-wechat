/* Byte-mode QR, ECC L, versions 1–10. Used to paint Native code_url. */
const BYTE_CAP = [0, 17, 32, 53, 78, 106, 134]
const DATA_CW = [0, 19, 34, 55, 80, 108, 136]
const EC_L = [0, 7, 10, 15, 20, 26, 36]
const ALIGN = {
  2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34],
  7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50],
}

function gfExp() {
  const exp = new Array(512)
  const log = new Array(256)
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

const GF = gfExp()

function rsGen(ec) {
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

function mul(a, b) {
  if (a === 0 || b === 0) {
    return 0
  }
  return GF.exp[GF.log[a] + GF.log[b]]
}

function rsEcc(data, ec) {
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

function bitsToBytes(bits) {
  const out = []
  for (let i = 0; i < bits.length; i += 8) {
    let v = 0
    for (let j = 0; j < 8 && i + j < bits.length; j++) {
      v = (v << 1) | bits[i + j]
    }
    if (bits.length - i < 8) {
      v <<= (8 - (bits.length - i))
    }
    out.push(v)
  }
  return out
}

function encodeData(text, version) {
  const bytes = []
  for (let i = 0; i < text.length; i++) {
    bytes.push(text.charCodeAt(i) & 0xff)
  }
  const cap = DATA_CW[version]
  const ec = EC_L[version]
  const bits = []
  const push = (val, n) => {
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
  const data = bitsToBytes(bits)
  const pad = [0xec, 0x11]
  let p = 0
  while (data.length < cap) {
    data.push(pad[p % 2])
    p++
  }
  return data.concat(rsEcc(data, ec))
}

function sizeOf(version) {
  return 17 + 4 * version
}

function reserve(grid, n) {
  const mark = (x, y) => {
    if (x >= 0 && y >= 0 && x < n && y < n) {
      grid[y][x] = 2
    }
  }
  const finder = (x, y) => {
    for (let dy = -1; dy <= 7; dy++) {
      for (let dx = -1; dx <= 7; dx++) {
        mark(x + dx, y + dy)
      }
    }
  }
  finder(0, 0)
  finder(n - 7, 0)
  finder(0, n - 7)
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
}

function placeFinders(mod, n) {
  const draw = (x, y) => {
    for (let dy = 0; dy < 7; dy++) {
      for (let dx = 0; dx < 7; dx++) {
        const on = dx === 0 || dx === 6 || dy === 0 || dy === 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4)
        mod[y + dy][x + dx] = on
      }
    }
  }
  draw(0, 0)
  draw(n - 7, 0)
  draw(0, n - 7)
}

function placeTiming(mod, n) {
  for (let i = 8; i < n - 8; i++) {
    mod[6][i] = i % 2 === 0
    mod[i][6] = i % 2 === 0
  }
}

function placeAlign(mod, version) {
  const pts = ALIGN[version]
  if (!pts) {
    return
  }
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

function maskFn(id, x, y) {
  switch (id) {
    case 0: return (x + y) % 2 === 0
    case 1: return y % 2 === 0
    case 2: return x % 3 === 0
    case 3: return (x + y) % 3 === 0
    case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0
    case 5: return ((x * y) % 2) + ((x * y) % 3) === 0
    case 6: return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0
    default: return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0
  }
}

function placeData(mod, reserved, data, mask, n) {
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
}

function bch(data) {
  let d = data << 10
  const g = 0x537
  for (let i = 14; i >= 10; i--) {
    if ((d >> i) & 1) {
      d ^= g << (i - 10)
    }
  }
  return ((data << 10) | d) ^ 0x5412
}

function placeFormat(mod, n, mask) {
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
}

function score(mod, n) {
  let s = 0
  for (let y = 0; y < n; y++) {
    let run = 1
    for (let x = 1; x < n; x++) {
      if (mod[y][x] === mod[y][x - 1]) {
        run++
      } else {
        if (run >= 5) {
          s += run - 2
        }
        run = 1
      }
    }
    if (run >= 5) {
      s += run - 2
    }
  }
  return s
}

function build(text, version, mask) {
  const n = sizeOf(version)
  const reserved = Array.from({ length: n }, () => new Array(n).fill(0))
  reserve(reserved, n)
  const mod = Array.from({ length: n }, () => new Array(n).fill(false))
  placeFinders(mod, n)
  placeTiming(mod, n)
  placeAlign(mod, version)
  const data = encodeData(text, version)
  placeData(mod, reserved, data, mask, n)
  placeFormat(mod, n, mask)
  return { mod, n, penalty: score(mod, n) }
}

function modules(text) {
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
  let best = null
  for (let mask = 0; mask < 8; mask++) {
    const built = build(text, version, mask)
    if (!best || built.penalty < best.penalty) {
      best = built
    }
  }
  return best.mod
}

module.exports = { modules }
