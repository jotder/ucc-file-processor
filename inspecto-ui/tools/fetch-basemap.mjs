// One-off: fetch Natural Earth GeoJSON + Noto glyphs into inspecto-ui/src/assets/basemap/
// Slims properties to what the map style needs, to keep the bundle small.
import { mkdir, writeFile } from 'node:fs/promises';

const OUT = 'C:/sandbox/ucc-file-processor/inspecto-ui/src/assets/basemap';
const NE = 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson';
const FONTS = 'https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts/Noto%20Sans%20Regular';

async function fetchJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${r.status} ${url}`);
  return r.json();
}

function slim(fc, keep = []) {
  for (const f of fc.features) {
    const p = {};
    for (const k of keep) if (f.properties?.[k] !== undefined) p[k] = f.properties[k];
    f.properties = p;
    delete f.id;
    delete f.bbox;
  }
  delete fc.bbox;
  return fc;
}

// Round coordinates to 4 decimals (~11 m) — big size win, invisible at basemap zooms.
function roundCoords(o) {
  if (Array.isArray(o)) {
    if (typeof o[0] === 'number') return o.map((n) => Math.round(n * 1e4) / 1e4);
    return o.map(roundCoords);
  }
  return o;
}
function roundFc(fc) {
  for (const f of fc.features) f.geometry.coordinates = roundCoords(f.geometry.coordinates);
  return fc;
}

await mkdir(`${OUT}/fonts/Noto Sans Regular`, { recursive: true });

const layers = [
  { file: 'land.geojson', src: `${NE}/ne_50m_land.geojson`, keep: [] },
  { file: 'boundaries.geojson', src: `${NE}/ne_50m_admin_0_boundary_lines_land.geojson`, keep: [] },
  { file: 'lakes.geojson', src: `${NE}/ne_50m_lakes.geojson`, keep: [] },
  { file: 'places.geojson', src: `${NE}/ne_50m_populated_places_simple.geojson`, keep: ['name', 'scalerank', 'featurecla'] },
];

for (const l of layers) {
  const fc = roundFc(slim(await fetchJson(l.src), l.keep));
  const json = JSON.stringify(fc);
  await writeFile(`${OUT}/${l.file}`, json);
  console.log(`${l.file}: ${(json.length / 1e6).toFixed(2)} MB, ${fc.features.length} features`);
}

// Glyph ranges: Basic Latin through Cyrillic (covers UI + common data labels).
const ranges = ['0-255', '256-511', '512-767', '768-1023', '1024-1279'];
for (const r of ranges) {
  const res = await fetch(`${FONTS}/${r}.pbf`);
  if (!res.ok) throw new Error(`font ${r}: ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await writeFile(`${OUT}/fonts/Noto Sans Regular/${r}.pbf`, buf);
  console.log(`font ${r}.pbf: ${(buf.length / 1024).toFixed(0)} KB`);
}
console.log('DONE');
