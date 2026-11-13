#!/usr/bin/env node
// Exercises the shipped application script with a minimal DOM/event adapter.
// This checks navigation and rendering logic, not browser layout or pixels.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const html = fs.readFileSync(path.join(root, 'docs/system-diagram.html'), 'utf8');
const payload = html.match(/<script type="application\/json" id="architecture-data">([\s\S]*?)<\/script>/)[1];
const script = html.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
const data = JSON.parse(payload);
assert(!html.includes('__ARCHITECTURE_DATA__'));
assert(!/<(?:script|link)[^>]+(?:src|href)="https?:/i.test(html), 'No remote dependencies');

function exercise(mobile) {
  const all = new Map(), windowEvents = {}, documentEvents = {};
  let document;
  class Element {
    constructor(tag = 'div') {
      this.tagName = tag.toUpperCase(); this.children = []; this.attrs = {};
      this.style = {}; this.events = {}; this.className = ''; this._text = '';
      this.hidden = false; this.value = ''; this.clientWidth = mobile ? 360 : 1100;
      this.clientHeight = 550;
      this.classList = {
        contains: c => this.className.split(' ').includes(c),
        add: c => { if (!this.classList.contains(c)) this.className += ' ' + c; },
        remove: c => { this.className = this.className.split(' ').filter(x => x !== c).join(' '); },
        toggle: c => { const result = !this.classList.contains(c); this.classList[result ? 'add' : 'remove'](c); return result; }
      };
    }
    set textContent(text) { this._text = String(text); this.children = []; }
    get textContent() { return this._text + this.children.map(c => c.textContent).join(''); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this._text = ''; this.children = children; }
    setAttribute(key, value) { this.attrs[key] = String(value); if (key === 'class') this.className = value; }
    getAttribute(key) { return this.attrs[key]; }
    addEventListener(name, fn) { (this.events[name] ||= []).push(fn); }
    fire(name, event = {}) { for (const fn of this.events[name] || []) fn({preventDefault() {}, target: this, ...event}); }
    click() { if (!this.disabled) this.fire('click'); }
    querySelectorAll(selector) { return this.children.flatMap(c => [...(c.tagName === selector.toUpperCase() ? [c] : []), ...c.querySelectorAll(selector)]); }
    focus() { document.activeElement = this; }
    scrollIntoView() { this.scrolled = true; }
    getBoundingClientRect() { return {left: 0, top: 0}; }
    setPointerCapture() {}
    remove() {}
    select() {}
    closest(selector) { return this.tagName === selector.toUpperCase() ? this : null; }
  }
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) all.set(match[1], new Element());
  all.get('architecture-data').textContent = payload;
  all.get('search').tagName = 'INPUT';
  document = {
    activeElement: null, body: new Element('body'),
    getElementById: id => { assert(all.has(id), 'Known DOM id: ' + id); return all.get(id); },
    createElement: tag => new Element(tag), createElementNS: (_, tag) => new Element(tag),
    createTextNode: text => { const e = new Element('text'); e.textContent = text; return e; },
    addEventListener: (name, fn) => { documentEvents[name] = fn; }, execCommand: () => true
  };
  let hash = '';
  const location = {href: 'file:///repo/docs/system-diagram.html'};
  Object.defineProperty(location, 'hash', {get: () => hash, set: v => {hash = v.startsWith('#') ? v : '#' + v; windowEvents.hashchange?.();}});
  const window = {addEventListener: (name, fn) => {windowEvents[name] = fn;}};
  const context = {document, window, location, navigator: {}, console,
    matchMedia: () => ({matches: mobile}), requestAnimationFrame: fn => fn(),
    ResizeObserver: class {constructor(fn) {this.fn = fn;} observe() {this.fn();}}
  };
  vm.runInNewContext(script, context, {filename: 'system-diagram.html'});
  const app = window.ArchitectureExplorer;
  assert.equal(app.current, 'system');
  assert.equal(app.levelFor('system'), 1);
  assert.equal(app.mode, mobile ? 'list' : 'map');
  assert.equal(all.get('up').disabled, true);
  assert.equal(all.get('list-view').children.length, 7);

  // The same controls used by visitors must reach every block at every depth.
  let routes = 0, sources = 0;
  for (const [id, node] of app.index) {
    if (id !== 'system') {
      const parent = app.ancestry(id).at(-2);
      app.navigate(parent.id);
      const button = all.get('list-view').children.find(b => b.getAttribute('aria-label').startsWith(node.title + '. '));
      assert(button, 'Clickable block: ' + id);
      button.click();
    }
    assert.equal(app.current, id);
    assert.equal(all.get('detail-title').textContent, node.title);
    assert(app.levelFor(id) >= 1 && app.levelFor(id) <= 4);
    assert.equal(app.view.id, node.children.length ? id : app.ancestry(id).at(-2).id);
    assert.equal(all.get('list-view').children.length, app.view.children.length);
    assert.equal(all.get('crumbs').children.length, app.ancestry(id).length);
    const layout = app.layout(app.view);
    for (const child of app.view.children) {
      const point = layout.points[child.id];
      assert(point.x >= 0 && point.y >= 0 && point.x + 240 <= layout.w && point.y + 160 <= layout.h);
    }
    for (const edge of all.get('connection-list').children.filter(e => e.tagName === 'BUTTON')) {
      edge.click(); assert.equal(all.get('contract').hidden, false); assert(all.get('contract').textContent);
      edge.click(); assert.equal(all.get('contract').hidden, true);
    }
    for (const source of all.get('detail-grid').querySelectorAll('a')) {
      assert(fs.existsSync(path.resolve(root, 'docs', decodeURIComponent(source.href))), source.href); sources++;
    }
    if (id !== 'system') {
      all.get('up').click(); assert.equal(app.current, app.ancestry(id).at(-2).id);
      all.get('home').click(); assert.equal(app.current, 'system');
    }
    routes++;
  }
  app.navigate('camera-unit-1');
  assert.equal(app.levelFor('camera-unit-1'), 4); assert.equal(app.view.id, 'camera');
  assert.equal(all.get('detail-title'), document.activeElement);
  assert.equal(all.get('detail').scrolled, true);
  const getToggle = id => all.get('tree').querySelectorAll('button').find(b => b.getAttribute('data-tree-id') === id);
  getToggle('phone').click(); assert.equal(document.activeElement, getToggle('phone'));
  getToggle('phone').click(); assert.equal(document.activeElement, getToggle('phone'));
  app.navigate('phone'); assert.equal(all.get('connection-title').textContent, 'Blocks in this view');
  app.navigate('camera'); assert.equal(all.get('connection-title').textContent, 'Implementation units');
  all.get('search').value = 'GPIO35'; all.get('search').fire('input');
  assert(all.get('results').children.some(b => b.tagName === 'BUTTON'));
  all.get('results').children.find(b => b.tagName === 'BUTTON').click();
  assert.notEqual(app.current, 'camera-unit-1');
  all.get('search').value = 'no-such-component-192847'; all.get('search').fire('input');
  assert.match(all.get('search-count').textContent, /^0 matching/);
  all.get('clear-search').click(); assert.equal(all.get('outline').hidden, false);
  assert(app.searchNodes('ConeDetector.java').length > 0);
  location.hash = '#not-a-block'; assert.equal(app.current, 'system'); assert.equal(all.get('route-warning').hidden, false);
  location.hash = '#%bad'; assert.equal(app.current, 'system');
  location.hash = '#phone'; assert.equal(all.get('route-warning').hidden, true);
  all.get('skip').click(); assert.equal(app.current, 'phone'); assert.equal(document.activeElement, all.get('main'));
  all.get('map-mode').click(); assert.equal(app.mode, 'map'); assert(Number.isFinite(app.camera.scale));
  const scale = app.camera.scale; all.get('zoom-in').click(); assert(app.camera.scale > scale);
  all.get('zoom-out').click(); all.get('fit').click(); assert.equal(all.get('stage').hidden, false);
  all.get('list-mode').click(); assert.equal(app.mode, 'list'); assert.equal(all.get('stage').hidden, true);
  document.activeElement = all.get('main'); documentEvents.keydown({key: '/', preventDefault() {}});
  assert.equal(document.activeElement, all.get('search'));
  document.activeElement = all.get('main'); documentEvents.keydown({key: 'Escape', preventDefault() {}});
  assert.equal(app.current, 'system');
  console.log(`${mobile ? 'Mobile' : 'Desktop'} adapter: ${routes} routes, ${sources} source links, drill-down / up / search / invalid hashes / focus / map controls passed`);
}
exercise(false); exercise(true);
