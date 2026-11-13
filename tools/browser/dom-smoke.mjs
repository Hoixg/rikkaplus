// Runs the application's actual DOM script in Chromium; no Android APK or website account is required.
// RIKKA_PLAYWRIGHT_MODULE may point to an existing playwright/index.mjs installation.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

const moduleName = process.env.RIKKA_PLAYWRIGHT_MODULE;
const { chromium } = await import(moduleName ? pathToFileURL(moduleName).href : 'playwright');
const implementation = await fs.readFile(new URL('../../app/src/main/assets/browser/automation.js', import.meta.url), 'utf8');
const asyncTemplate = await fs.readFile(new URL('../../app/src/main/assets/browser/async.js', import.meta.url), 'utf8');
const browser = await chromium.launch({ channel: process.env.RIKKA_BROWSER_CHANNEL || 'msedge', headless: true });
const page = await browser.newPage({ viewport: { width: 640, height: 900 } });
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
const execute = body => page.evaluate(({ body, asyncTemplate, id }) => new Promise((resolve, reject) => {
  window.__rikkaBrowser ??= {};
  window.__rikkaBrowser.complete = (responseId, encoded) => {
    if (responseId !== id) return;
    const response = JSON.parse(encoded);
    if (response.ok === false) reject(new Error(response.error));
    else resolve(response.value);
  };
  (0, eval)(asyncTemplate.replace('__RIKKA_ID__', JSON.stringify(id)).replace('__RIKKA_BODY__', () => body));
}), { body, asyncTemplate, id: randomUUID() });
const action = async (name, input = {}) => JSON.parse(await execute(
  `const action = ${JSON.stringify(name)}; const args = ${JSON.stringify({max_chars:12000,include_text:true,snapshot_id:randomUUID(),...input})}; ${implementation}`
));

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
  await page.locator('#counter').evaluate(el => {
    el.addEventListener('dblclick', () => { el.dataset.doubles = String(Number(el.dataset.doubles || 0) + 1); });
    el.addEventListener('contextmenu', event => { event.preventDefault(); el.dataset.contexts = String(Number(el.dataset.contexts || 0) + 1); });
  });
  await action('click', {ref:counterRef, click_count:2});
  check(await page.locator('#counter').getAttribute('data-count') === '3', 'Kelivo double click sends exactly two clicks');
  check(await page.locator('#counter').getAttribute('data-doubles') === '1', 'Kelivo double click dispatches dblclick');
  await action('click', {ref:counterRef, button:'right'});
  check(await page.locator('#counter').getAttribute('data-contexts') === '1', 'Kelivo right click dispatches contextmenu');
  check(await page.locator('#counter').getAttribute('data-count') === '3', 'right click does not also send a left click');
  await action('type', {ref:inputRef, text:' appended', clear_first:false});
  check(await page.locator('#name').inputValue() === '测试 Alice appended', 'Kelivo input can append without clearing');
  await action('type', {ref:inputRef, text:'replacement'});
  check(await page.locator('#name').inputValue() === 'replacement', 'Kelivo input clears the previous value by default');
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

  await page.setContent(`<!doctype html><button id="feedback-button" onclick="this.dataset.count=String(Number(this.dataset.count||0)+1)">Feedback</button>
    <input id="feedback-input"><div id="feedback-scroll" style="overflow:auto;width:240px;height:140px"><div style="height:1600px">Scrollable</div></div>`);
  await page.evaluate(() => {
    window.feedbackTrace = [];
    window.__rikkaBrowser = {showGesture(encoded) { window.feedbackTrace.push(JSON.parse(encoded)); }};
  });
  check((await action('click', {selector:'#feedback-button'})).clicked, 'click remains functional with feedback enabled');
  const tapFeedback = await page.evaluate(() => window.feedbackTrace.at(-1));
  const buttonBounds = await page.locator('#feedback-button').boundingBox();
  check(tapFeedback.kind === 'tap' && tapFeedback.from_x >= buttonBounds.x && tapFeedback.from_x <= buttonBounds.x+buttonBounds.width &&
    tapFeedback.from_y >= buttonBounds.y && tapFeedback.from_y <= buttonBounds.y+buttonBounds.height, 'tap feedback reports the resolved target position');
  check(tapFeedback.width === 640 && tapFeedback.height === 900, 'feedback carries CSS viewport dimensions');
  check((await action('type', {selector:'#feedback-input',text:'Feedback input'})).verified, 'input verification remains unchanged with feedback');
  check(await page.evaluate(() => window.feedbackTrace.at(-1).kind === 'tap'), 'input target receives visual feedback');
  await action('scroll', {selector:'#feedback-scroll',direction:'down',amount:300});
  const scrollFeedback = await page.evaluate(() => window.feedbackTrace.at(-1));
  check(scrollFeedback.kind === 'scroll' && scrollFeedback.from_y > scrollFeedback.to_y, 'scroll-down feedback moves the finger upward');
  check(await page.locator('#feedback-scroll').evaluate(el => el.scrollTop) === 300, 'feedback preserves actual scrolling');
  await action('scroll', {selector:'#feedback-scroll',direction:'down',amount:9000});
  const feedbackCount = await page.evaluate(() => window.feedbackTrace.length);
  await action('scroll', {selector:'#feedback-scroll',direction:'down',amount:9000});
  check(await page.evaluate(() => window.feedbackTrace.length) === feedbackCount, 'scroll boundaries do not draw fictitious movement');
  await page.evaluate(() => { window.__rikkaBrowser.showGesture = () => { throw new Error('visual failure'); }; });
  check((await action('click', {selector:'#feedback-button'})).clicked && await page.locator('#feedback-button').getAttribute('data-count') === '2',
    'a failed visual callback does not prevent or duplicate the action');

  check((await execute('return await Promise.resolve({text:"异步完成"});')).text === '异步完成', 'native completion awaits Promise values');
  check(await execute('return undefined;') === null, 'undefined results become JSON null');
  await assert.rejects(execute('throw new Error("sync failure");'), /sync failure/); checks++;
  await assert.rejects(execute('return await Promise.reject(new Error("async failure"));'), /async failure/); checks++;
  await assert.rejects(execute('return 1n;'), /BigInt/); checks++;
  check(await execute('return "__RIKKA_ID__ __RIKKA_BODY__";') === '__RIKKA_ID__ __RIKKA_BODY__', 'script contents are not rewritten as template placeholders');

  let pendingImage;
  await page.route('https://browser.test/pending.png', route => { pendingImage = route; });
  await page.route('https://browser.test/dom-ready', route => route.fulfill({
    contentType:'text/html',body:'<!doctype html><p>Ready</p><img src="/pending.png">',
  }));
  await page.goto('https://browser.test/dom-ready', {waitUntil:'domcontentloaded'});
  check(await execute(`if (document.readyState === 'loading') await new Promise(resolve => document.addEventListener('DOMContentLoaded',resolve,{once:true})); return document.readyState;`) === 'interactive',
    'DOM-ready completion does not wait for a pending image');
  await pendingImage.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>'});
  await page.waitForLoadState('load');
  check(await execute('return document.readyState;') === 'complete', 'explicit full load can wait for remaining resources');
  console.log(JSON.stringify({ checks, failures: 0 }));
} finally {
  await browser.close();
}
