// Runs the application's actual DOM script in Chromium; no Android APK or website account is required.
// RIKKA_PLAYWRIGHT_MODULE may point to an existing playwright/index.mjs installation.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

const moduleName = process.env.RIKKA_PLAYWRIGHT_MODULE;
const { chromium } = await import(moduleName ? pathToFileURL(moduleName).href : 'playwright');
const implementation = await fs.readFile(new URL('../../app/src/main/assets/browser/automation.js', import.meta.url), 'utf8');
const browser = await chromium.launch({ channel: process.env.RIKKA_BROWSER_CHANNEL || 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 640, height: 900 } });
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
const action = async (name, input = {}) => page.evaluate(async ({ name, input, implementation }) => {
  const value = await new Function('action', 'args', implementation)(name, {
    max_chars: 12000, include_text: true, ...input,
  });
  return JSON.parse(value);
}, { name, input: { snapshot_id: randomUUID(), ...input }, implementation });

try {
  await page.setContent(`<!doctype html><html><head><title>Browser fixture</title></head><body>
    <main><h1>Fixture report</h1><p id="status">Ready for browser actions</p>
    <form onsubmit="event.preventDefault();document.querySelector('#status').textContent='Submitted '+document.querySelector('#name').value;">
      <input id="name" placeholder="Your name"><input type="password" value="fixture-secret" aria-label="Password">
      <button id="submit" type="submit">Send form</button>
      <select id="choice" aria-label="Colour"><option value="red">Red</option><option value="blue">Blue</option></select>
    </form><button id="counter" onclick="this.dataset.count=String(Number(this.dataset.count||0)+1)">Count once</button>
    <button class="ambiguous">Duplicate</button><button class="ambiguous">Duplicate</button>
    <div id="shadow"></div><iframe id="frame" srcdoc='<button onclick="this.textContent=&#39;Frame clicked&#39;">Frame button</button>'></iframe>
    <div style="height:1200px"></div><button id="bottom" onclick="this.textContent='Bottom clicked'">Bottom button</button>
    </main></body></html>`);
  await page.evaluate(() => { const root = document.querySelector('#shadow').attachShadow({ mode: 'open' }); root.innerHTML = '<button onclick="this.textContent=\'Shadow clicked\'">Shadow button</button>'; });
  await page.locator('#frame').contentFrame().getByRole('button').waitFor();
  const snapshot = await action('snapshot');
  check(snapshot.ok, 'snapshot succeeds');
  check(snapshot.viewport.width === 640 && snapshot.viewport.height === 900, 'coordinates use viewport CSS dimensions');
  check(snapshot.text.includes('Ready for browser actions'), 'snapshot includes confirmation text outside controls');
  check(!JSON.stringify(snapshot).includes('fixture-secret'), 'password values are not returned');
  check(snapshot.elements.some(e => e.name === 'Shadow button'), 'open shadow roots are traversed');
  check(snapshot.elements.some(e => e.name === 'Frame button'), 'same-origin frame content is traversed');
  const inputRef = snapshot.elements.find(e => e.name === 'Your name').ref;
  const counterRef = snapshot.elements.find(e => e.name === 'Count once').ref;
  check((await action('type', { ref: inputRef, text: '测试 Alice' })).verified, 'input is set and verified');
  check(await page.locator('#name').inputValue() === '测试 Alice', 'native DOM retains exact Unicode text');
  check((await action('click', { ref: counterRef })).clicked, 'unrelated input updates do not expire another element ref');
  check(await page.locator('#counter').getAttribute('data-count') === '1', 'one click produces one mutation');
  check((await action('select_option', { selector: '#choice', option_value: 'blue' })).verified, 'select_option matches its schema');
  check(await page.locator('#choice').inputValue() === 'blue', 'selected value reaches the form');
  check((await action('press', { selector: '#name', key: 'Enter' })).pressed === 'Enter', 'Enter is accepted');
  check((await page.locator('#status').innerText()).includes('Submitted 测试 Alice'), 'Enter submits the current form');
  const ambiguous = await action('click', { selector: '.ambiguous' });
  check(!ambiguous.ok && ambiguous.error.includes('ambiguous_selector'), 'ambiguous targets are reported');
  await page.evaluate(() => document.querySelector('#counter').replaceWith(document.querySelector('#counter').cloneNode(true)));
  const stale = await action('click', { ref: counterRef });
  check(!stale.ok && stale.error.includes('stale_ref'), 'replaced targets require a fresh snapshot');
  check((await action('click', { selector: '#bottom' })).clicked, 'off-screen targets are scrolled into view');
  check(await page.locator('#bottom').innerText() === 'Bottom clicked', 'scrolled target is correctly clicked');
  check((await action('extract', { format: 'markdown', max_chars: 40 })).truncated, 'long extracted content is paginated');
  const extraction = await action('extract', { format: 'text', max_chars: 30, offset: 30 });
  check(extraction.offset === 30 && extraction.content.length <= 30, 'extraction offset advances');
  const outside = await action('click', { x: 650, y: 950 });
  check(!outside.ok && outside.error.includes('coordinates_outside_viewport'), 'coordinates outside the viewport are rejected consistently');
  // The frame may be off-screen after the scroll, so a unique frame selector is tested through a new viewport.
  await page.evaluate(() => scrollTo(0, 0));
  const top = await action('snapshot');
  const frame = top.elements.find(e => e.name === 'Frame button');
  check(!!frame, 'same-origin frame is visible after restoring the viewport');
  check((await action('click', { ref: frame.ref })).clicked, 'frame coordinates and reference work together');
  check(await page.locator('#frame').contentFrame().getByRole('button').innerText() === 'Frame clicked', 'frame mutation succeeds');
  const shadow = top.elements.find(e => e.name === 'Shadow button');
  check((await action('click', { ref: shadow.ref })).clicked, 'shadow-root reference remains usable');
  check(await page.locator('#shadow').getByRole('button').innerText() === 'Shadow clicked', 'shadow-root mutation succeeds');
  console.log(JSON.stringify({ checks, failures: 0 }));
} finally {
  await browser.close();
}
