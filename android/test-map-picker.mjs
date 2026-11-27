import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const html = readFileSync(new URL('./app/src/main/assets/map_picker.html', import.meta.url), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
assert.equal(scripts.length, 1, 'expected one inline map script');

const width = 330;
const height = 340;
const listeners = new Map();
const tileImages = [];
const selectedPoints = [];

const mapElement = {
  style: {},
  getBoundingClientRect: () => ({ left: 0, top: 0, width, height }),
  addEventListener: (name, handler) => listeners.set(name, handler),
  setPointerCapture: () => {},
};
const tilesElement = {
  appendChild(image) { tileImages.push(image); },
};
const pinElement = { style: {}, hidden: true };
const hintElement = { textContent: '' };
const baseElement = { textContent: '' };
const elements = new Map([
  ['map', mapElement],
  ['tiles', tilesElement],
  ['pin', pinElement],
  ['hint', hintElement],
  ['base', baseElement],
]);
const document = {
  documentElement: { style: {} },
  body: { style: {} },
  getElementById: id => elements.get(id),
  createElement: tag => {
    assert.equal(tag, 'img');
    const image = {
      style: {},
      remove() {
        const index = tileImages.indexOf(image);
        if (index >= 0) tileImages.splice(index, 1);
      },
    };
    return image;
  },
};
const window = {
  AndroidMap: {
    onPoint: (latitude, longitude) => selectedPoints.push([latitude, longitude]),
  },
  addEventListener: () => {},
};

runInNewContext(scripts[0][1], { document, window, location: { protocol: 'file:' } });
window.initMap(39.9042, 116.4074, false, 340);
assert.equal(document.documentElement.style.height, '340px');
assert.equal(document.body.style.height, '340px');
assert.equal(mapElement.style.height, '340px');
assert.ok(tileImages.length > 0, 'initialization should request visible tiles');

function pointer(name, x, y) {
  const handler = listeners.get(name);
  assert.equal(typeof handler, 'function', `missing ${name} handler`);
  handler({
    pointerId: 1,
    clientX: x,
    clientY: y,
    target: { closest: () => null },
    preventDefault() {},
  });
}
function tap(x, y) {
  pointer('pointerdown', x, y);
  pointer('pointerup', x, y);
}

// Beijing center round-trip should preserve GCJ-02 coordinates to six decimals.
tap(width / 2, height / 2);
assert.deepEqual(selectedPoints.at(-1), ['39.904200', '116.407400']);

// A tap just across the wrapped world edge must stay inside valid longitude bounds.
window.initMap(0, 179.9999, false);
tap(width - 1, height / 2);
const datelineLongitude = Number(selectedPoints.at(-1)[1]);
assert.ok(datelineLongitude >= -180 && datelineLongitude <= 180, `longitude out of range: ${datelineLongitude}`);
assert.ok(datelineLongitude < -179.9, `expected wrapped longitude west of -179.9°, got ${datelineLongitude}`);

window.zoomBy(1);
assert.ok(tileImages.length > 0 && tileImages.every(image => image.src.includes('z=17')), 'zoom should redraw tiles at z=17');
window.toggleBase();
assert.ok(tileImages.length > 0 && tileImages.every(image => image.src.includes('style=6')), 'base toggle should request satellite tiles');

const pointCount = selectedPoints.length;
pointer('pointerdown', width / 2, height / 2);
pointer('pointermove', width / 2 - 40, height / 2 + 12);
pointer('pointerup', width / 2 - 40, height / 2 + 12);
assert.equal(selectedPoints.length, pointCount, 'a pan must not be treated as a tap');

tap(width / 2, height / 2);
assert.equal(selectedPoints.length, pointCount + 1, 'a subsequent tap should select a point');

console.log('Map picker tests passed: center round-trip, date-line wrap, zoom/base switch, and pan-vs-tap.');
