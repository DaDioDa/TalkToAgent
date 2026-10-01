// @ts-check
// Disposable simulation only. No microphone, network, clipboard or persistent storage.
const variants = ['A', 'B', 'C'];
const names = ['下方大圓鈕', '底部操作列', '下半部觸控區'];
/** @template {HTMLElement} T @param {string} id @returns {T} */
function element(id) { return /** @type {T} */ (document.getElementById(id)); }
const primary = /** @type {HTMLButtonElement} */ (element('primary'));
const cancel = /** @type {HTMLButtonElement} */ (element('cancel'));
const outcome = /** @type {HTMLSelectElement} */ (element('outcome'));
const panel = /** @type {HTMLDialogElement} */ (element('panel'));
const phone = /** @type {HTMLElement} */ (document.querySelector('.phone'));
let variant = 'A';
/** @type {keyof typeof statuses} */
let state = 'ready';
let connected = true;
let recentText = '';
let hapticCue = '開始與結束會顯示輕震提示';
/** @type {number | undefined} */
let timer;
const sampleText = '請幫我整理今天的待辦事項，並列出下一步。';
const statuses = {
  ready: ['○', '輸入就緒', '點一下開始，再點一下結束。\n轉錄定稿後自動送往電腦。'],
  recording: ['●', '正在錄音', '再點一下結束，自動送往電腦。\n右下角取消只會丟棄本次錄音。'],
  cancelled: ['○', '已取消', '本次錄音已丟棄，沒有送往電腦。\n可以開始新的一段。'],
  finalizing: ['◌', '正在轉錄定稿', '請稍候，定稿後會自動傳送。'],
  transmitting: ['◌', '正在傳送', '請保持電腦的輸入位置。\n目前無法開始新的一段。'],
  completed: ['✓', '貼上操作完成', '電腦已發出貼上操作。\n不保證目標程式已接受文字。'],
  disconnected: ['×', '電腦未連線', '點上方電腦名稱管理連線。\n連線恢復後不會自動重送。'],
  unknown: ['?', '輸入結果不明', '請查看電腦，可能已完成貼上操作。\n不會自動重送；確認後再開始。']
};
function busy() { return state === 'finalizing' || state === 'transmitting'; }
function render() {
  const status = statuses[state];
  element('status-symbol').textContent = status[0];
  element('status-title').textContent = status[1];
  element('status-detail').textContent = status[2];
  element('haptic-cue').textContent = hapticCue;
  element('connection-label').textContent = `書桌電腦 · ${connected ? '已連線' : '未連線'}`;
  element('connection-dot').textContent = connected ? '●' : '○';
  primary.disabled = busy() || !connected;
  cancel.disabled = state !== 'recording';
  element('primary-label').textContent = state === 'recording' ? '結束並送出' : busy() ? '處理中…' : !connected ? '等待連線' : '開始說話';
  element('primary-hint').textContent = state === 'recording' ? '再點一下結束' : '點一下開始';
  element('state-readout').textContent = `variant=${variant}\nstate=${state}\ncomputer=${connected ? 'connected' : 'disconnected'}\nrecentTranscript=${recentText ? 'available (hidden on home)' : 'none'}\nnextOutcome=${outcome.value}`;
}
function stopTimer() { window.clearTimeout(timer); timer = undefined; }
/** @param {string} message */
function cue(message) {
  hapticCue = message + '（模擬輕震）';
  if (/** @type {HTMLInputElement} */ (element('vibrate')).checked && typeof navigator.vibrate === 'function') navigator.vibrate(20);
}
/** @param {string} result */
function transmit(result) {
  state = 'transmitting';
  render();
  timer = window.setTimeout(() => {
    state = result === 'completed' ? 'completed' : 'unknown';
    if (result === 'disconnected') connected = false;
    render();
  }, 1400);
}
function finishRecording() {
  const result = outcome.value; // Freeze the selected outcome for this operation.
  cue('錄音結束');
  state = 'finalizing';
  render();
  timer = window.setTimeout(() => {
    recentText = sampleText;
    transmit(result);
  }, 1200);
}
primary.addEventListener('click', () => {
  if (busy() || !connected) return;
  if (state === 'recording') { finishRecording(); return; }
  state = 'recording';
  cue('錄音開始');
  render();
});
cancel.addEventListener('click', () => {
  if (state !== 'recording') return;
  state = 'cancelled';
  hapticCue = '本次錄音已丟棄';
  render();
});
function disconnect() {
  const previous = state;
  stopTimer();
  connected = false;
  // After dispatch, loss of connection cannot prove that paste did not happen.
  state = previous === 'transmitting' || previous === 'unknown' ? 'unknown' : 'disconnected';
  hapticCue = previous === 'recording' ? '連線中斷，本次錄音已丟棄' : '模擬連線中斷';
  render();
}
element('disconnect').addEventListener('click', disconnect);
element('reset').addEventListener('click', () => {
  stopTimer();
  state = 'ready'; connected = true; recentText = '';
  outcome.value = 'completed';
  hapticCue = '開始與結束會顯示輕震提示';
  if (panel.open) panel.close();
  render();
});
outcome.addEventListener('change', render);
/** @param {string} value */
function setVariant(value) {
  variant = variants.includes(value) ? value : 'A';
  phone.dataset.variant = variant;
  element('variant-label').textContent = `${variant} · ${names[variants.indexOf(variant)]}`;
  const url = new URL(window.location.href);
  url.searchParams.set('variant', variant);
  // file:// can reject replaceState in some browsers; navigation still keeps the variant shareable.
  try { window.history.replaceState(null, '', url); }
  catch { if (new URLSearchParams(window.location.search).get('variant') !== variant) window.location.search = url.search; }
  render();
}
/** @param {number} direction */
function cycle(direction) { setVariant(variants[(variants.indexOf(variant) + direction + variants.length) % variants.length]); }
element('previous').addEventListener('click', () => cycle(-1));
element('next').addEventListener('click', () => cycle(1));
document.addEventListener('keydown', event => {
  const target = /** @type {HTMLElement | null} */ (event.target);
  if (panel.open || target?.closest('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) return;
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); cycle(event.key === 'ArrowLeft' ? -1 : 1); }
});
window.addEventListener('popstate', () => setVariant(new URLSearchParams(window.location.search).get('variant') || 'A'));
/** @param {string} title @param {string} html */
function openPanel(title, html) {
  element('panel-title').textContent = title;
  element('panel-body').innerHTML = html;
  if (!panel.open) panel.showModal();
}
element('close-panel').addEventListener('click', () => panel.close());
element('connection').addEventListener('click', () => {
  openPanel('連線管理', `<p>固定電腦：書桌電腦</p><p>${connected ? '已連線（模擬）' : '未連線（模擬）'}。沒有進行真實配對或授權。</p><button id="reconnect" ${connected ? 'disabled' : ''}>模擬重新連線</button><p>恢復連線不會重送上一段文字。</p>`);
  element('reconnect').addEventListener('click', () => {
    connected = true;
    // Retain an ambiguous operation's warning until the user deliberately starts a NEW recording.
    if (state !== 'unknown') state = 'ready';
    render(); panel.close();
  });
});
element('recent').addEventListener('click', () => {
  openPanel('最近轉錄', '<p>僅保留本次開啟期間的最近一段，沒有歷史紀錄。</p><blockquote id="transcript"></blockquote><p>查看不會再次傳送，也不能撤回電腦貼上。</p>');
  element('transcript').textContent = recentText || '尚無轉錄定稿。';
});
function diagnostics() {
  openPanel('診斷 / 測試工具', '<p>以下只測試畫面，不會將文字送到電腦。</p><label for="typed">手動測試文字</label><textarea id="typed" rows="3" placeholder="輸入測試文字"></textarea><button id="test-send">模擬定稿與傳送</button><p>下一次結果由原型控制台設定；處理期間禁止重複傳送。</p>');
  const send = /** @type {HTMLButtonElement} */ (element('test-send'));
  send.disabled = busy() || state === 'recording' || !connected;
  send.addEventListener('click', () => {
    const text = /** @type {HTMLTextAreaElement} */ (element('typed')).value.trim();
    if (!text || busy() || state === 'recording' || !connected) return;
    recentText = text;
    transmit(outcome.value);
    panel.close();
  });
}
element('settings').addEventListener('click', () => {
  openPanel('設定', '<p>操作：點一下開始，再點一下結束。定稿自動送出，不需確認。</p><p>輕震：開始 / 結束；無提示音。此處僅呈現模擬提示。</p><button id="diagnostics">診斷與手動測試</button>');
  element('diagnostics').addEventListener('click', diagnostics);
});
setVariant(new URLSearchParams(window.location.search).get('variant') || 'A');
