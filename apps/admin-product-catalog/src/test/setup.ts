/**
 * Node 25+ ships its own global `localStorage`/`sessionStorage`, which are undefined unless Node
 * is started with `--localstorage-file`, and they shadow jsdom's. Put jsdom's real Storage objects
 * back so specs (and `Storage.prototype` spies) see the browser behaviour. TD-ADM-11 / TD-AI-09.
 */
const dom = (globalThis as { jsdom?: { window: Window } }).jsdom;
if (dom) {
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    Object.defineProperty(globalThis, name, {
      configurable: true,
      get: () => dom.window[name],
    });
  }
}
