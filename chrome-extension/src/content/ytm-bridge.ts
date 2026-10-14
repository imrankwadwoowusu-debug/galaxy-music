// Isolated-world relay: chrome.runtime <-> page (MAIN world) via postMessage.
chrome.runtime.sendMessage({ type: 'helper-hello' }).catch(() => {});

window.addEventListener('message', (e) => {
  if (e.source !== window || !e.data?.__bc || e.data.__bc === 'cmd') return;
  const { __bc: kind, payload } = e.data;
  chrome.runtime.sendMessage({ type: 'page-event', kind, payload }).catch(() => {});
});

chrome.runtime.onMessage.addListener((msg) => {
  if (msg?.type === 'page-cmd') window.postMessage({ __bc: 'cmd', payload: msg.command }, location.origin);
});
