// Generates the app icons in public/ from one geometry (no dependencies; `npm run icons`).
// Original artwork for "stocks": a rising price line with a live dot on a dark tile.
// Outputs: icon.svg (favicon), icon-192.png, icon-512.png, icon-maskable-512.png, apple-touch-icon.png (180).
import { writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { fileURLToPath } from 'node:url'

const out = (name) => fileURLToPath(new URL(`../public/${name}`, import.meta.url))

const BG = [0x15, 0x18, 0x1e]
const UP = [0x22, 0xc9, 0x8a]
const LINE = [
  [112, 340],
  [200, 268],
  [262, 306],
  [400, 170],
]
const STROKE = 40
const DOT_R = 34
const AREA_ALPHA = 0.16
const AREA_BOTTOM = 392

const hex = (c) => `#${c.map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase()}`

function svg({ radius }) {
  const pts = LINE.map(([x, y]) => `${x},${y}`).join(' ')
  const area = `${pts} ${LINE.at(-1)[0]},${AREA_BOTTOM} ${LINE[0][0]},${AREA_BOTTOM}`
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="${radius}" fill="${hex(BG)}"/>
  <polygon points="${area}" fill="${hex(UP)}" fill-opacity="${AREA_ALPHA}"/>
  <polyline points="${pts}" fill="none" stroke="${hex(UP)}" stroke-width="${STROKE}" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="${LINE.at(-1)[0]}" cy="${LINE.at(-1)[1]}" r="${DOT_R}" fill="${hex(UP)}"/>
</svg>
`
}

function distToSegment(px, py, [ax, ay], [bx, by]) {
  const dx = bx - ax
  const dy = by - ay
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

function inPolygon(x, y, poly) {
  let inside = false
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i]
    const [xj, yj] = poly[j]
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

function inRoundRect(x, y, r) {
  if (x < 0 || y < 0 || x > 512 || y > 512) return false
  if (r === 0) return true
  const cx = Math.max(r, Math.min(512 - r, x))
  const cy = Math.max(r, Math.min(512 - r, y))
  return Math.hypot(x - cx, y - cy) <= r
}

// Colour of one sample in 512-space; motif scaled about the centre by `scale` (maskable safe zone).
function sample(x, y, { radius, scale }) {
  if (!inRoundRect(x, y, radius)) return [0, 0, 0, 0]
  const mx = 256 + (x - 256) / scale
  const my = 256 + (y - 256) / scale
  let c = [...BG]
  const area = [...LINE, [LINE.at(-1)[0], AREA_BOTTOM], [LINE[0][0], AREA_BOTTOM]]
  if (inPolygon(mx, my, area)) c = c.map((v, i) => v * (1 - AREA_ALPHA) + UP[i] * AREA_ALPHA)
  let d = Infinity
  for (let i = 0; i < LINE.length - 1; i++) d = Math.min(d, distToSegment(mx, my, LINE[i], LINE[i + 1]))
  const end = LINE.at(-1)
  if (d <= STROKE / 2 || Math.hypot(mx - end[0], my - end[1]) <= DOT_R) c = [...UP]
  return [...c, 255]
}

function render(size, opts) {
  const SS = 4
  const px = Buffer.alloc(size * size * 4)
  for (let py = 0; py < size; py++) {
    for (let pxi = 0; pxi < size; pxi++) {
      let r = 0, g = 0, b = 0, a = 0
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const x = ((pxi + (sx + 0.5) / SS) * 512) / size
          const y = ((py + (sy + 0.5) / SS) * 512) / size
          const [cr, cg, cb, ca] = sample(x, y, opts)
          r += cr * ca; g += cg * ca; b += cb * ca; a += ca
        }
      }
      const o = (py * size + pxi) * 4
      px[o] = a ? Math.round(r / a) : 0
      px[o + 1] = a ? Math.round(g / a) : 0
      px[o + 2] = a ? Math.round(b / a) : 0
      px[o + 3] = Math.round(a / (SS * SS))
    }
  }
  return png(size, size, px)
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(buf) {
  let c = 0xffffffff
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function png(w, h, rgba) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  const raw = Buffer.alloc((w * 4 + 1) * h)
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4)
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

writeFileSync(out('icon.svg'), svg({ radius: 112 }))
writeFileSync(out('icon-192.png'), render(192, { radius: 112, scale: 1 }))
writeFileSync(out('icon-512.png'), render(512, { radius: 112, scale: 1 }))
// Maskable: full-bleed tile, motif inside the 80% safe zone.
writeFileSync(out('icon-maskable-512.png'), render(512, { radius: 0, scale: 0.78 }))
// iOS masks the corners itself and renders transparency as black, so the touch icon is full-bleed.
writeFileSync(out('apple-touch-icon.png'), render(180, { radius: 0, scale: 0.86 }))
console.log('icons written to public/')
