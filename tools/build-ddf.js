#!/usr/bin/env node
// Builds this device's Device Description File from ddf-source/ - the DDF's
// real editable source (device.json + adornment.svg + fonts/) - and writes
// the zip into the designer repo, where it is served as a built-in device.
//
// Why the source lives here and not there: the DDF says what this device can
// render, and that is a fact about this app, decided in this repo. Every
// other ScreenBee target already works that way (screenbee-waveshare-1v8's
// own ddf-source/ + tools/generate-ddf-header.js), and the one time it did
// not - the M5 Dial, whose zip was only ever hand-assembled - the designer's
// copy and the device's real capabilities drifted apart, so anyone picking
// that device got a DDF missing object types the device actually had. The
// Android zip was hand-assembled the same way until now.
//
// A firmware compiles its DDF in and serves it over HTTP; this app has no
// such endpoint, so "publishing" it means writing the file the designer
// ships. That is the only structural difference.
//
// Run after any change to ddf-source/:
//
//   node tools/build-ddf.js
//
// Pass --check to verify without writing (exit 1 on drift). The output is
// byte-deterministic by construction (fixed DOS timestamps, entries in a
// fixed order), so --check only ever fires on a real change, never on
// wall-clock drift.
//
// Node built-ins only: this repo is a Gradle project with no package.json to
// hang a zip library off, and the same constraint produced the same
// hand-rolled writer in the Waveshare's script, which this one follows.

const fs = require("fs")
const path = require("path")
const zlib = require("zlib")
const crypto = require("crypto")

const REPO = path.join(__dirname, "..")
const SOURCE_DIR = path.join(REPO, "ddf-source")
const DEFAULT_OUT = path.join(REPO, "..", "v0-screenman-editor-design", "public", "ddf", "android-phone.ddf.zip")

const checkOnly = process.argv.includes("--check")
const outPath = process.argv.slice(2).find((a) => !a.startsWith("--")) || DEFAULT_OUT

if (!fs.existsSync(path.join(SOURCE_DIR, "device.json"))) {
  console.error(`DDF source not found: ${SOURCE_DIR} (expected a device.json in it)`)
  process.exit(1)
}

// Standard ZIP/PKZIP CRC-32 (same polynomial as gzip/PNG).
const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

// Fixed DOS date/time (1980-01-01, the format's own epoch) rather than the
// real mtime: rebuilding from unchanged source has to produce byte-identical
// output or --check reports "stale" on every run.
const DOS_TIME = 0
const DOS_DATE = (1 << 5) | 1

function buildZip(entries) {
  const localParts = []
  const centralParts = []
  let offset = 0

  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, "utf8")
    const crc = crc32(data)
    const compressed = zlib.deflateRawSync(data)
    const useStore = compressed.length >= data.length
    const method = useStore ? 0 : 8
    const payload = useStore ? data : compressed

    const localHeader = Buffer.alloc(30)
    localHeader.writeUInt32LE(0x04034b50, 0)
    localHeader.writeUInt16LE(20, 4)
    localHeader.writeUInt16LE(0, 6)
    localHeader.writeUInt16LE(method, 8)
    localHeader.writeUInt16LE(DOS_TIME, 10)
    localHeader.writeUInt16LE(DOS_DATE, 12)
    localHeader.writeUInt32LE(crc, 14)
    localHeader.writeUInt32LE(payload.length, 18)
    localHeader.writeUInt32LE(data.length, 22)
    localHeader.writeUInt16LE(nameBuf.length, 26)
    localHeader.writeUInt16LE(0, 28)
    localParts.push(localHeader, nameBuf, payload)

    const centralHeader = Buffer.alloc(46)
    centralHeader.writeUInt32LE(0x02014b50, 0)
    centralHeader.writeUInt16LE(20, 4)
    centralHeader.writeUInt16LE(20, 6)
    centralHeader.writeUInt16LE(0, 8)
    centralHeader.writeUInt16LE(method, 10)
    centralHeader.writeUInt16LE(DOS_TIME, 12)
    centralHeader.writeUInt16LE(DOS_DATE, 14)
    centralHeader.writeUInt32LE(crc, 16)
    centralHeader.writeUInt32LE(payload.length, 20)
    centralHeader.writeUInt32LE(data.length, 24)
    centralHeader.writeUInt16LE(nameBuf.length, 28)
    centralHeader.writeUInt16LE(0, 30)
    centralHeader.writeUInt16LE(0, 32)
    centralHeader.writeUInt16LE(0, 34)
    centralHeader.writeUInt16LE(0, 36)
    centralHeader.writeUInt32LE(0, 38)
    centralHeader.writeUInt32LE(offset, 42)
    centralParts.push(centralHeader, nameBuf)

    offset += localHeader.length + nameBuf.length + payload.length
  }

  const centralDirStart = offset
  const centralDir = Buffer.concat(centralParts)

  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(0, 4)
  eocd.writeUInt16LE(0, 6)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(centralDir.length, 12)
  eocd.writeUInt32LE(centralDirStart, 16)
  eocd.writeUInt16LE(0, 20)

  return Buffer.concat([...localParts, centralDir, eocd])
}

// Sorted by name so the order is a property of the source, not of the order
// readdir happened to return.
function collectEntries(dir, prefix = "") {
  const entries = []
  for (const name of fs.readdirSync(dir).sort()) {
    const full = path.join(dir, name)
    const zipName = prefix + name
    if (fs.statSync(full).isDirectory()) {
      entries.push(...collectEntries(full, `${zipName}/`))
    } else {
      entries.push({ name: zipName, data: fs.readFileSync(full) })
    }
  }
  return entries
}

const entries = collectEntries(SOURCE_DIR)
const zip = buildZip(entries)
const hash = crypto.createHash("sha256").update(zip).digest("hex")

if (checkOnly) {
  const existing = fs.existsSync(outPath) ? fs.readFileSync(outPath) : null
  if (existing && existing.equals(zip)) {
    console.log(`up to date: ${outPath} (sha256 ${hash.slice(0, 16)})`)
    process.exit(0)
  }
  console.error(`stale: ${outPath} does not match ddf-source/ - run node tools/build-ddf.js`)
  process.exit(1)
}

fs.mkdirSync(path.dirname(outPath), { recursive: true })
fs.writeFileSync(outPath, zip)
console.log(`wrote ${outPath}`)
console.log(`  ${entries.length} entries, ${zip.length} bytes, sha256 ${hash.slice(0, 16)}`)
for (const entry of entries) console.log(`  - ${entry.name} (${entry.data.length} bytes)`)
