// Isolated-world relay: chrome.runtime <-> page (MAIN world) via postMessage.
// After an extension update the old copy loses its runtime; it then stops
// relaying instead of throwing, and the background injects a fresh one.
const W = window as unknown as { __bcBridge?: number };
const me = (W.__bcBridge = (W.__bcBridge ?? 0) + 1);
const alive = () => W.__bcBridge === me && !!chrome.runtime?.id;
const post = (msg: unknown) => { if (alive()) chrome.runtime.sendMessage(msg).catch(() => {}); };

post({ type: 'helper-hello' });

window.addEventListener('message', (e) => {
  if (e.source !== window || !e.data?.__bc || e.data.__bc === 'cmd' || !alive()) return;
  const { __bc: kind, payload } = e.data;
  post({ type: 'page-event', kind, payload });
});

chrome.runtime.onMessage.addListener((msg, _s, reply) => {
  if (!alive() || msg?.type !== 'page-cmd') return;
  window.postMessage({ __bc: 'cmd', payload: msg.command }, location.origin);
  reply(true);
});
