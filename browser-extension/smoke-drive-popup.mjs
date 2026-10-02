// Exercise the real popup handlers and port responses with a minimal DOM fixture.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-popup-')), 'popup.mjs');
await esbuild.build({
  entryPoints: ['src/popup/drive-popup.ts'], bundle: true, format: 'esm', platform: 'browser',
  target: 'es2022', outfile: out, logLevel: 'silent',
  define: { __TEMPO_BROWSER_TARGET__: JSON.stringify('chrome') },
});
const status = {
  enabled: true, configured: true, connected: false, needsInteractiveAuth: true,
  accountEmail: 'test@example.com', lastError: null, lastSyncTime: null, lastUploaded: 0, lastImported: 0,
};
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 5; i++) await tick(); }

async function openPopup(initial, responseStatus, instance) {
  const commands = [];
  const nodes = new Map();
  const makeNode = () => ({ style: {}, disabled: false, textContent: '', handlers: {},
    addEventListener(type, callback) { this.handlers[type] = callback; },
    querySelector() { return null; },
    appendChild(child) { nodes.set(child.id, child); },
  });
  for (const id of ['tab-settings', 'drive-sync-account', 'drive-sync-status',
    'btn-drive-connect', 'btn-drive-sync', 'btn-drive-disconnect', 'btn-drive-delete']) {
    nodes.set(id, makeNode());
  }
  globalThis.document = { getElementById: id => nodes.get(id) ?? null, createElement: makeNode };
  globalThis.window = { setTimeout, clearTimeout };
  globalThis.chrome = { runtime: { connect() {
    let receive;
    return {
      onMessage: { addListener(fn) { receive = fn; } },
      onDisconnect: { addListener() {} }, disconnect() {},
      postMessage({ command }) {
        commands.push(command);
        queueMicrotask(() => receive({ ok: true, status: command === 'status' ? initial : responseStatus }));
      },
    };
  } } };
  await import(`${pathToFileURL(out).href}?instance=${instance}`);
  await settle();
  return { nodes, commands };
}

let popup = await openPopup(status, { ...status, connected: true, needsInteractiveAuth: false }, 1);
assert.equal(popup.nodes.get('btn-drive-connect').style.display, '');
assert.equal(popup.nodes.get('btn-drive-connect').textContent, 'Reconnect Google');
assert.equal(popup.nodes.get('btn-drive-sync').style.display, 'none');
assert.equal(popup.nodes.get('btn-drive-delete').style.display, 'none');
popup.nodes.get('btn-drive-connect').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status', 'connect']);
assert.equal(popup.nodes.get('btn-drive-connect').style.display, 'none');
assert.equal(popup.nodes.get('btn-drive-sync').style.display, '');
console.log('  ✓ expired authorization offers an interactive reconnect and restores sync controls');

popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, configured: false }, 2);
popup.nodes.get('btn-drive-sync').handlers.click();
await settle();
assert.equal(popup.nodes.get('btn-drive-connect').disabled, true);
assert.equal(popup.nodes.get('btn-drive-sync').style.display, 'none');
console.log('  ✓ operation completion preserves the unavailable OAuth configuration state');
console.log('\n2 Drive popup scenarios passed');
