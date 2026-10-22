/**
 * Dashboard owner presensi (PWA statis). UI saja; logika ada di rekap.js, jaringan di api.js.
 * Keamanan: semua teks dari server (nama karyawan, catatan, nama HP) dipasang lewat textContent / text node,
 * tidak pernah lewat innerHTML. Kunci owner tidak pernah dicatat ke konsol dan tidak pernah masuk URL query.
 */
import * as api from './api.js';
import * as R from './rekap.js';

const LS = {
  key: 'presensi.ownerKey',
  site: 'presensi.site',
  tab: 'presensi.tab',
  sites: 'presensi.cache.sites',
  todayPrefix: 'presensi.cache.today.',
};
const TABS = ['today', 'rekap', 'karyawan', 'pengaturan', 'hp'];
const REFRESH_MS = 60 * 1000;
const PAIRING_POLL_MS = 10 * 1000;

// ---------------------------------------------------------------- penyimpanan lokal (aman bila diblokir)

const store = {
  get(k) {
    try {
      return localStorage.getItem(k);
    } catch {
      return null;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(k, v);
    } catch {
      /* penyimpanan penuh / diblokir: abaikan */
    }
  },
  remove(k) {
    try {
      localStorage.removeItem(k);
    } catch {
      /* abaikan */
    }
  },
  keys() {
    try {
      return Object.keys(localStorage);
    } catch {
      return [];
    }
  },
  json(k) {
    const t = this.get(k);
    if (!t) return null;
    try {
      return JSON.parse(t);
    } catch {
      return null;
    }
  },
};

// ---------------------------------------------------------------- state

const state = {
  key: null,
  sites: [],
  siteId: null,
  tab: 'today',
  /** owner_overview untuk hari ini: dipakai tab Hari ini, Karyawan, Pengaturan, HP. */
  base: null,
  baseSite: null,
  baseDate: null,
  baseAt: 0,
  baseTriedAt: 0,
  baseError: null,
  baseOffline: false,
  baseLoading: false,
  /** serverTime - jam browser (ms), untuk "online N menit lalu" dan hitung mundur kode. */
  clockOffset: 0,
  rekap: { ym: null, data: null, loading: false, error: null, open: new Set() },
  form: null,
  pendingConflict: null,
  pairing: null,
  loginError: '',
  loginBusy: false,
};
let baseSeq = 0;
let rekapSeq = 0;

const $ = (id) => document.getElementById(id);
const serverNow = () => Date.now() + state.clockOffset;

// ---------------------------------------------------------------- pembuat DOM (tanpa innerHTML)

const PROP_KEYS = new Set(['value', 'checked', 'disabled', 'selected', 'hidden', 'open', 'indeterminate']);

function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || (v === false && !PROP_KEYS.has(k))) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
      else if (PROP_KEYS.has(k)) el[k] = v;
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false || c === '') continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
const ICON = {
  pin: 'M12 2a7 7 0 0 0-7 7c0 5.25 7 13 7 13s7-7.75 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z',
  open: 'M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7zM19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-2v7z',
  left: 'M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z',
  right: 'M8.59 16.59 10 18l6-6-6-6-1.41 1.41L13.17 12z',
  copy: 'M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  plus: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  phone: 'M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z',
  gps: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm8.94 3A8.99 8.99 0 0 0 13 3.06V1h-2v2.06A8.99 8.99 0 0 0 3.06 11H1v2h2.06A8.99 8.99 0 0 0 11 20.94V23h2v-2.06A8.99 8.99 0 0 0 20.94 13H23v-2h-2.06zM12 19a7 7 0 1 1 0-14 7 7 0 0 1 0 14z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  warn: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
};

function icon(name, cls = 'ic') {
  const s = document.createElementNS(SVG_NS, 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('aria-hidden', 'true');
  s.setAttribute('class', cls);
  const p = document.createElementNS(SVG_NS, 'path');
  p.setAttribute('d', ICON[name]);
  s.append(p);
  return s;
}

function button(label, onclick, { kind = '', iconName = null, type = 'button', disabled = false, attrs = {} } = {}) {
  return h('button', { type, class: `btn ${kind}`.trim(), onclick, disabled, ...attrs }, iconName ? icon(iconName) : null, h('span', null, label));
}

// ---------------------------------------------------------------- toast & dialog

let toastTimer = 0;

function toast(message, { action = null, kind = '' } = {}) {
  const t = $('toast');
  const parts = [h('span', { class: 'toast-text' }, message)];
  if (action) {
    parts.push(h('button', { type: 'button', class: 'toast-action', onclick: () => { t.hidden = true; action.onClick(); } }, action.label));
  }
  t.replaceChildren(...parts);
  t.className = `toast ${kind ? `toast-${kind}` : ''}`.trim();
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    t.hidden = true;
  }, action ? 10000 : 4500);
}

function openDialog(title, body, actions) {
  const dlg = $('dialog');
  dlg.replaceChildren(h('h2', { id: 'dialog-title', class: 'dialog-title' }, title), body, h('div', { class: 'dialog-actions' }, actions));
  if (typeof dlg.showModal === 'function') {
    if (!dlg.open) dlg.showModal();
  } else {
    dlg.setAttribute('open', '');
  }
}

function closeDialog() {
  const dlg = $('dialog');
  if (typeof dlg.close === 'function') {
    if (dlg.open) dlg.close();
  } else {
    dlg.removeAttribute('open');
  }
}

function confirmDialog({ title, message, confirmLabel = 'Ya', danger = false }) {
  const dlg = $('dialog');
  return new Promise((resolve) => {
    let done = false;
    const finish = (value) => {
      if (done) return;
      done = true;
      dlg.removeEventListener('close', onClose);
      closeDialog();
      resolve(value);
    };
    const onClose = () => finish(false);
    dlg.addEventListener('close', onClose);
    openDialog(title, h('p', { class: 'dialog-text' }, message), [
      button('Batal', () => finish(false), { kind: 'btn-ghost' }),
      button(confirmLabel, () => finish(true), { kind: danger ? 'btn-danger' : 'btn-primary' }),
    ]);
  });
}

// ---------------------------------------------------------------- salin & unduh

async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* coba cara lama */
  }
  const ta = h('textarea', { class: 'offscreen', readonly: true });
  ta.value = text;
  document.body.append(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  ta.remove();
  return ok;
}

/** Bila clipboard ditolak browser: tampilkan teks untuk disalin manual. */
function showTextForManualCopy(title, text) {
  const ta = h('textarea', { class: 'manual-copy', readonly: true, rows: '10' });
  ta.value = text;
  openDialog(title, h('div', null, h('p', { class: 'dialog-text' }, 'Browser menolak menyalin otomatis. Tekan lama teks di bawah, pilih semua, lalu salin.'), ta), [
    button('Tutup', closeDialog, { kind: 'btn-primary' }),
  ]);
  ta.focus();
  ta.select();
}

function downloadText(fileName, text, type) {
  const blob = new Blob([text], { type });
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: fileName, class: 'offscreen' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

// ---------------------------------------------------------------- bantuan tampilan

function settingsOf(payload) {
  return R.normalizeSettings(payload?.site?.defaultSettings, payload?.settings?.data);
}

function currentSite() {
  return state.sites.find((s) => s.id === state.siteId) ?? { id: state.siteId, name: state.siteId ?? '' };
}

function siteWords() {
  const site = currentSite();
  return R.siteText(site.id, state.base?.site?.name ?? site.name);
}

function avatar(emp) {
  const initial = (String(emp.name ?? '').trim()[0] ?? '?').toUpperCase();
  return h('span', { class: `avatar ${emp.gender === 'P' ? 'avatar-p' : 'avatar-l'}`, 'aria-hidden': 'true' }, initial);
}

function pill(text, kind) {
  return h('span', { class: `pill pill-${kind}` }, text);
}

function statusKind(status) {
  return { TEPAT: 'ok', TELAT: 'bad', IZIN: 'warn', SAKIT: 'info' }[status] ?? 'neutral';
}

function genderLabel(g) {
  return g === 'P' ? 'Perempuan' : 'Laki-laki';
}

function card(...children) {
  return h('div', { class: 'card' }, children);
}

function loadingCard(text = 'Memuat data…') {
  return h('div', { class: 'card card-muted' }, h('p', { class: 'muted center' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), ' ', text));
}

function errorCard(message, retry) {
  return h(
    'div',
    { class: 'card card-error', role: 'alert' },
    h('p', { class: 'card-title' }, 'Gagal memuat data'),
    h('p', null, message),
    retry ? button('Coba lagi', retry, { kind: 'btn-primary' }) : null,
  );
}

function notice(kind, ...children) {
  return h('div', { class: `notice notice-${kind}` }, kind === 'warn' || kind === 'bad' ? icon('warn') : null, h('div', null, children));
}

function sectionTitle(text, extra = null) {
  return h('div', { class: 'section-title' }, h('h2', null, text), extra);
}

function timeOfDay(ms) {
  const d = new Date(ms);
  return `${R.pad2(d.getHours())}:${R.pad2(d.getMinutes())}`;
}

// ---------------------------------------------------------------- masuk / keluar

function readKeyFromFragment() {
  const hash = location.hash || '';
  if (!/[#&]k=/.test(hash)) return null;
  const params = new URLSearchParams(hash.slice(1));
  const key = R.normalizeOwnerKey(params.get('k'));
  // Buang kunci dari address bar (tidak ikut riwayat, bookmark, atau tangkapan layar). Kunci baru disimpan
  // oleh signIn() setelah server menerimanya, jadi tautan lama tidak menghapus kunci yang masih berlaku.
  params.delete('k');
  const rest = params.toString();
  try {
    history.replaceState(history.state, '', `${location.pathname}${location.search}${rest ? `#${rest}` : ''}`);
  } catch {
    location.hash = '';
  }
  if (!key) state.loginError = 'Kunci di tautan tidak valid (harus 64 karakter heksadesimal).';
  return key;
}

function clearSession() {
  state.key = null;
  store.remove(LS.key);
  store.remove(LS.sites);
  for (const k of store.keys()) if (k.startsWith(LS.todayPrefix)) store.remove(k);
  state.sites = [];
  state.base = null;
  state.baseSite = null;
  state.rekap = { ym: null, data: null, loading: false, error: null, open: new Set() };
  state.form = null;
  state.pendingConflict = null;
  state.pairing = null;
  baseSeq++;
  rekapSeq++;
}

function handleAuth(e) {
  if (!(e instanceof api.ApiError) || !e.isAuth) return false;
  clearSession();
  state.loginError = 'Kunci owner tidak valid atau sudah diganti. Masukkan kunci owner yang baru.';
  showLogin();
  return true;
}

async function signIn(key, { announce = false } = {}) {
  state.loginBusy = true;
  if (document.body.classList.contains('is-login')) renderLogin();
  try {
    const sites = await api.ownerSites(key);
    if (!Array.isArray(sites) || sites.length === 0) throw new api.ApiError('Server belum punya tempat kerja.');
    state.key = key;
    store.set(LS.key, key);
    store.set(LS.sites, JSON.stringify(sites));
    state.sites = sites.filter((s) => s && typeof s.id === 'string');
    state.loginError = '';
    enterApp();
    if (announce) toast('Berhasil masuk. Kunci owner disimpan di browser ini.');
  } catch (e) {
    if (e instanceof api.ApiError && e.isAuth) {
      const stored = R.normalizeOwnerKey(store.get(LS.key));
      if (stored && stored !== key) {
        // Kunci dari tautan/ketikan ditolak, tetapi kunci yang tersimpan berbeda: pakai yang tersimpan.
        toast('Kunci di tautan tidak valid. Memakai kunci owner yang tersimpan di browser ini.', { kind: 'bad' });
        state.loginBusy = false;
        return signIn(stored);
      }
      clearSession();
      state.loginError = 'Kunci owner tidak valid. Periksa lagi kuncinya.';
      showLogin();
      return;
    }
    const cached = store.json(LS.sites);
    if (e.network && Array.isArray(cached) && cached.length > 0 && store.get(LS.key) === key) {
      // Offline: buka dengan daftar tempat terakhir; data dicoba dimuat (cache hari ini bila ada).
      state.key = key;
      state.sites = cached;
      enterApp();
      toast(e.message, { kind: 'bad' });
    } else {
      state.loginError = e.message;
      showLogin();
    }
  } finally {
    state.loginBusy = false;
    if (document.body.classList.contains('is-login')) renderLogin();
  }
}

async function logout() {
  const ok = await confirmDialog({
    title: 'Keluar dari dashboard?',
    message: 'Kunci owner akan dihapus dari browser ini. Untuk masuk lagi, buka tautan kunci owner atau tempel kuncinya.',
    confirmLabel: 'Keluar',
    danger: true,
  });
  if (!ok) return;
  clearSession();
  state.loginError = '';
  showLogin();
  toast('Sudah keluar. Kunci owner dihapus dari browser ini.');
}

function showLogin() {
  document.body.classList.remove('is-booting', 'is-auth');
  document.body.classList.add('is-login');
  for (const t of TABS) $(`view-${t}`).hidden = true;
  $('view-boot').hidden = true;
  $('view-login').hidden = false;
  stopPairingTimers();
  renderLogin();
}

function renderLogin() {
  const v = $('view-login');
  const stored = R.normalizeOwnerKey(store.get(LS.key));
  const input = h('input', {
    id: 'login-key', name: 'owner-key', type: 'password', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false',
    placeholder: '64 karakter 0-9 dan a-f', 'aria-describedby': 'login-help', disabled: state.loginBusy,
  });
  const err = h('p', { class: 'form-error', role: 'alert', id: 'login-error' }, state.loginError);
  const showKey = h('input', { type: 'checkbox', id: 'login-show', onchange: () => { input.type = showKey.checked ? 'text' : 'password'; } });
  const form = h(
    'form',
    {
      class: 'card login-card', novalidate: true,
      onsubmit: (e) => {
        e.preventDefault();
        const key = R.normalizeOwnerKey(input.value);
        if (!key) {
          state.loginError = 'Kunci owner harus 64 karakter heksadesimal (angka 0-9 dan huruf a-f).';
          err.textContent = state.loginError;
          input.focus();
          return;
        }
        state.loginError = '';
        signIn(key, { announce: true });
      },
    },
    h('h2', null, 'Masuk dashboard owner'),
    h('p', { id: 'login-help', class: 'muted' }, 'Buka tautan kunci owner yang diberikan (sekali saja), atau tempel kunci owner di sini. Kunci hanya disimpan di browser ini dan tidak dikirim ke mana pun selain server presensi.'),
    h('label', { for: 'login-key', class: 'field-label' }, 'Kunci owner'),
    input,
    h('label', { class: 'check-row', for: 'login-show' }, showKey, h('span', null, 'Tampilkan kunci')),
    err,
    h('div', { class: 'btn-row' },
      button(state.loginBusy ? 'Memeriksa kunci…' : 'Masuk', null, { kind: 'btn-primary', type: 'submit', disabled: state.loginBusy }),
      stored && !state.loginBusy ? button('Coba lagi dengan kunci tersimpan', () => signIn(stored), { kind: 'btn-ghost' }) : null,
    ),
  );
  v.replaceChildren(form, h('p', { class: 'muted small center' }, 'Dashboard ini hanya membaca data presensi (tanpa foto) dan mengubah pengaturan dari jarak jauh. Presensi tetap dilakukan di HP presensi.'));
}

function chooseSite() {
  const saved = store.get(LS.site);
  state.siteId = state.sites.some((s) => s.id === saved) ? saved : state.sites[0]?.id ?? null;
}

function enterApp() {
  document.body.classList.remove('is-booting', 'is-login');
  document.body.classList.add('is-auth');
  $('view-login').hidden = true;
  $('view-boot').hidden = true;
  chooseSite();
  const savedTab = store.get(LS.tab);
  state.tab = TABS.includes(savedTab) ? savedTab : 'today';
  renderHeader();
  setTab(state.tab, { load: false });
  loadBase();
  if (state.tab === 'rekap') ensureRekap();
}

// ---------------------------------------------------------------- header, tempat, tab

function renderHeader() {
  $('today-label').textContent = R.longDateLabel(R.localDateString());
  const sw = $('site-switch');
  sw.replaceChildren(
    ...state.sites.map((s) =>
      h('button', {
        type: 'button', role: 'radio', class: 'site-btn', 'aria-checked': s.id === state.siteId ? 'true' : 'false',
        onclick: () => switchSite(s.id),
      }, s.name),
    ),
  );
  sw.hidden = state.sites.length < 2;
  for (const b of document.querySelectorAll('#tabs .tab')) {
    if (b.dataset.tab === state.tab) b.setAttribute('aria-current', 'page');
    else b.removeAttribute('aria-current');
  }
  $('btn-refresh').classList.toggle('spinning', state.baseLoading || state.rekap.loading);
}

async function switchSite(id) {
  if (id === state.siteId) return;
  if (state.form?.dirty) {
    const ok = await confirmDialog({
      title: 'Buang perubahan pengaturan?',
      message: 'Pengaturan yang Anda ubah belum disimpan. Pindah tempat kerja akan membuang perubahan itu.',
      confirmLabel: 'Pindah dan buang',
      danger: true,
    });
    if (!ok) return;
  }
  state.siteId = id;
  store.set(LS.site, id);
  state.base = null;
  state.baseSite = null;
  state.baseError = null;
  state.form = null;
  state.pendingConflict = null;
  state.pairing = null;
  stopPairingTimers();
  state.rekap.data = null;
  state.rekap.error = null;
  state.rekap.open = new Set();
  renderHeader();
  renderCurrent();
  loadBase();
  if (state.tab === 'rekap') loadRekap();
}

function setTab(tab, { load = true } = {}) {
  state.tab = TABS.includes(tab) ? tab : 'today';
  store.set(LS.tab, state.tab);
  for (const t of TABS) $(`view-${t}`).hidden = t !== state.tab;
  renderHeader();
  renderCurrent();
  window.scrollTo(0, 0);
  if (state.tab !== 'hp') stopPairingTimers();
  else if (state.pairing) startPairingTimers();
  if (!load) return;
  if (state.tab === 'rekap') ensureRekap();
  else if (isBaseStale()) loadBase({ quiet: true });
}

/** Data "hari ini" perlu dimuat ulang: belum ada, sudah >= 1 menit sejak percobaan terakhir, atau tanggal berganti. */
function isBaseStale() {
  if (state.baseLoading) return false;
  if (!state.base) return !state.baseError || Date.now() - state.baseTriedAt >= REFRESH_MS;
  return Date.now() - state.baseTriedAt >= REFRESH_MS || state.baseDate !== R.localDateString();
}

function renderCurrent() {
  if (!state.key) return;
  renderHeader();
  switch (state.tab) {
    case 'today':
      renderToday();
      break;
    case 'rekap':
      renderRekap();
      break;
    case 'karyawan':
      renderKaryawan();
      break;
    case 'pengaturan':
      renderPengaturan();
      break;
    case 'hp':
      renderHp();
      break;
    default:
      break;
  }
}

function refreshCurrent() {
  if (!state.key) return;
  if (state.tab === 'rekap') loadRekap();
  else loadBase();
}

// ---------------------------------------------------------------- pemuatan data

async function loadBase({ quiet = false } = {}) {
  if (!state.key || !state.siteId) return;
  const site = state.siteId;
  const today = R.localDateString();
  const seq = ++baseSeq;
  state.baseLoading = true;
  state.baseTriedAt = Date.now();
  if (!quiet) state.baseError = null;
  renderHeader();
  if (!state.base) renderCurrent();
  try {
    const t0 = Date.now();
    const data = await api.ownerOverview(state.key, site, today, today);
    if (seq !== baseSeq) return;
    const t1 = Date.now();
    const st = R.parseTimestamp(data?.serverTime);
    if (st != null) state.clockOffset = st - Math.round((t0 + t1) / 2);
    state.base = data;
    state.baseSite = site;
    state.baseDate = today;
    state.baseAt = t1;
    state.baseError = null;
    state.baseOffline = false;
    store.set(LS.todayPrefix + site, JSON.stringify({ date: today, at: t1, data }));
    checkPairingDone();
  } catch (e) {
    if (seq !== baseSeq) return;
    if (handleAuth(e)) return;
    const cached = store.json(LS.todayPrefix + site);
    if (e.network && !state.base && cached && cached.date === today && cached.data) {
      state.base = cached.data;
      state.baseSite = site;
      state.baseDate = today;
      state.baseAt = cached.at ?? 0;
      state.baseOffline = true;
    }
    state.baseError = e.message;
  } finally {
    if (seq === baseSeq) {
      state.baseLoading = false;
      renderCurrent();
    }
  }
}

/** Data rekap ikut berubah setelah pengaturan/karyawan disimpan: muat ulang saat tab Rekap dibuka. */
function invalidateRekap() {
  state.rekap.data = null;
  if (state.tab === 'rekap') loadRekap();
}

function ensureRekap() {
  const rk = state.rekap;
  const thisMonth = R.ymOf(R.localDateString());
  if (!rk.ym || rk.ym > thisMonth) rk.ym = thisMonth;
  const fresh = rk.data && rk.data.siteId === state.siteId && rk.data.ym === rk.ym && Date.now() - rk.data.at < REFRESH_MS;
  if (!fresh && !rk.loading) loadRekap();
}

async function loadRekap() {
  if (!state.key || !state.siteId) return;
  const rk = state.rekap;
  const thisMonth = R.ymOf(R.localDateString());
  if (!rk.ym || rk.ym > thisMonth) rk.ym = thisMonth;
  const ym = rk.ym;
  const site = state.siteId;
  const seq = ++rekapSeq;
  const { from, to } = R.monthRange(ym);
  rk.loading = true;
  rk.error = null;
  renderCurrent();
  try {
    const payload = await api.ownerOverview(state.key, site, from, to);
    if (seq !== rekapSeq) return;
    rk.data = { siteId: site, ym, payload, at: Date.now() };
  } catch (e) {
    if (seq !== rekapSeq) return;
    if (handleAuth(e)) return;
    rk.error = e.message;
  } finally {
    if (seq === rekapSeq) {
      rk.loading = false;
      renderCurrent();
    }
  }
}

/** Banner di atas tab yang memakai data "hari ini": offline / gagal memperbarui. */
function baseStatusNotice() {
  if (state.base && state.baseDate && state.baseDate !== R.localDateString()) {
    return notice('bad', h('strong', null, 'Belum ada data hari ini. '),
      `Yang tampil di bawah adalah data ${R.longDateLabel(state.baseDate)}, bukan hari ini. `,
      state.baseError ? `Gagal memperbarui: ${state.baseError}` : 'Sedang memuat data hari ini…');
  }
  if (state.baseOffline) {
    return notice('warn', h('strong', null, 'Offline. '), `Menampilkan data terakhir (diambil ${timeOfDay(state.baseAt)}). `, state.baseError ?? '');
  }
  if (state.baseError && state.base) {
    return notice('bad', h('strong', null, 'Gagal memperbarui. '), state.baseError, ` Data di bawah dari ${timeOfDay(state.baseAt)}.`);
  }
  return null;
}

/** Tampilan saat data "hari ini" belum ada: memuat / gagal. Mengembalikan null bila data siap. */
function baseGate(view) {
  if (state.base && state.baseSite === state.siteId) return false;
  view.replaceChildren(state.baseError ? errorCard(state.baseError, () => loadBase()) : loadingCard());
  return true;
}

// ---------------------------------------------------------------- tab: Hari ini

function renderToday() {
  const v = $('view-today');
  if (baseGate(v)) return;
  const b = state.base;
  const s = settingsOf(b);
  const today = state.baseDate;
  const day = R.dayInfo(today, s);
  const nowTime = R.localTimeString();
  const employees = (b.employees ?? []).filter((e) => e.active);
  const recs = new Map((b.attendance ?? []).filter((a) => a.date === today).map((a) => [a.employeeId, a]));
  const counts = { TEPAT: 0, TELAT: 0, IZIN: 0, SAKIT: 0, BELUM: 0, overdue: 0 };
  const rows = employees.map((emp) => {
    const rec = recs.get(emp.id) ?? null;
    const st = R.todayStatus(emp, rec, s, nowTime, day.isWorkDay);
    counts[st.kind] = (counts[st.kind] ?? 0) + 1;
    if (st.overdue) counts.overdue++;
    return employeeTodayCard(emp, rec, st, s);
  });

  const nodes = [baseStatusNotice()];
  if (day.holiday) {
    nodes.push(notice('warn', h('strong', null, `Hari libur: ${day.holiday.name || 'Libur'}. `), 'Hari ini tidak dihitung hari kerja (tidak ada alpa). Presensi tetap bisa dicatat.'));
  } else if (!day.isWorkDay) {
    nodes.push(notice('warn', h('strong', null, 'Hari ini bukan hari kerja. '), 'Tidak dihitung alpa. Presensi tetap bisa dicatat.'));
  }
  nodes.push(
    h('div', { class: 'card card-accent' },
      h('div', { class: 'summary-row' },
        h('div', null, h('p', { class: 'card-title' }, 'Jam masuk'), rulesLines(s)),
        h('div', { class: 'summary-count' }, h('span', { class: 'big-num' }, String(counts.TEPAT + counts.TELAT)), h('span', { class: 'muted small' }, `dari ${employees.length} hadir`)),
      ),
      h('div', { class: 'chips' },
        pill(`Tepat ${counts.TEPAT}`, 'ok'),
        pill(`Telat ${counts.TELAT}`, 'bad'),
        counts.IZIN + counts.SAKIT > 0 ? pill(`Izin/Sakit ${counts.IZIN + counts.SAKIT}`, 'warn') : null,
        counts.BELUM > 0 ? pill(`Belum absen ${counts.BELUM}`, counts.overdue > 0 ? 'bad-outline' : 'neutral') : null,
      ),
    ),
  );
  nodes.push(sectionTitle(`Karyawan ${b.site?.name ?? ''}`.trim()));
  nodes.push(rows.length ? h('div', { class: 'list' }, rows) : card(h('p', { class: 'muted' }, (b.employees ?? []).length === 0
      ? 'Belum ada karyawan di cloud. Hubungkan HP presensi dulu (tab HP); daftar karyawan dikirim otomatis saat HP pertama kali sinkron.'
      : 'Belum ada karyawan aktif. Aktifkan atau tambahkan di tab Karyawan.')));
  nodes.push(sectionTitle('HP presensi'));
  nodes.push(devicesSummary(b));
  nodes.push(
    h('p', { class: 'muted small center updated' },
      state.baseAt ? `Diperbarui ${timeOfDay(state.baseAt)} · otomatis tiap 1 menit saat halaman terbuka` : '',
    ),
  );
  v.replaceChildren(...nodes.filter(Boolean));
}

/** Aturan jam masuk: satu baris bila sama untuk semua, selain itu satu baris per gender. */
function rulesLines(s) {
  if (R.sameRuleForAll(s)) return h('p', { class: 'strong nowrap' }, R.shortRulesText(s));
  return h('div', null,
    h('p', { class: 'strong nowrap' }, `Perempuan ${s.workStartFemale} (+${s.toleranceFemale} mnt)`),
    h('p', { class: 'strong nowrap' }, `Laki-laki ${s.workStartMale} (+${s.toleranceMale} mnt)`),
  );
}

function employeeTodayCard(emp, rec, st, s) {
  const kind = st.kind === 'BELUM' ? (st.overdue ? 'bad' : 'neutral') : statusKind(st.kind);
  const loc = R.locationBadge(rec);
  const meta = [];
  if (loc) {
    const parts = [loc.text, loc.distance, loc.accuracy].filter(Boolean).join(' · ');
    meta.push(h('span', { class: `loc ${loc.inLocation ? 'loc-in' : 'loc-out'}` }, icon('pin'), h('span', null, parts)));
    if (!loc.inLocation && loc.note && loc.note !== 'di luar lokasi') meta.push(h('span', { class: 'muted small' }, `(${loc.note})`));
    if (loc.mapUrl) {
      meta.push(h('a', { class: 'map-link', href: loc.mapUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Peta', icon('open', 'ic ic-sm')));
    }
  }
  const sub = [];
  if (st.kind === 'BELUM') {
    sub.push(st.overdue ? `Lewat batas ${R.limitLabel(s, emp.gender)}` : `Batas tepat ${R.limitLabel(s, emp.gender)}`);
  }
  if (rec?.device) sub.push(`dari ${rec.device}`);
  return h('div', { class: `card emp-card ${st.overdue ? 'emp-overdue' : ''}`.trim() },
    avatar(emp),
    h('div', { class: 'emp-main' },
      h('div', { class: 'emp-head' },
        h('div', { class: 'emp-name' }, h('span', { class: 'strong' }, emp.name), emp.role ? h('span', { class: 'muted small' }, emp.role) : null),
        pill(st.text, kind),
      ),
      meta.length ? h('div', { class: 'emp-meta' }, meta) : null,
      rec?.note ? h('p', { class: 'note' }, rec.note) : null,
      sub.length ? h('p', { class: 'muted small' }, sub.join(' · ')) : null,
    ),
  );
}

function deviceLines(d, current) {
  const ds = R.deviceStatus(d, serverNow(), current);
  return {
    ds,
    nodes: [
      h('p', { class: `dev-seen ${ds.stale ? 'dev-stale' : 'dev-ok'}` }, h('span', { class: 'dot', 'aria-hidden': 'true' }), ds.lastSeenText),
      ds.stale && ds.lastSeenMs != null
        ? h('p', { class: 'small dev-stale' }, 'Lebih dari 26 jam tidak sinkron. Periksa HP: internet, baterai, atau aplikasi presensi tidak dibuka.')
        : null,
      h('p', { class: 'muted small' },
        [
          ds.appVersion ? `Versi aplikasi ${ds.appVersion}` : 'Versi aplikasi belum diketahui',
          ds.unsynced == null ? null : ds.unsynced === 0 ? 'semua presensi terkirim' : `${ds.unsynced} presensi belum terkirim`,
        ].filter(Boolean).join(' · '),
      ),
      ds.waitingSync
        ? h('p', null, pill(ds.appliedVersion > 0 ? `menunggu HP sinkron (pengaturan v${ds.appliedVersion} → v${current})` : `menunggu HP sinkron (pengaturan v${current})`, 'warn'))
        : null,
    ],
  };
}

function devicesSummary(b) {
  const devices = b.devices ?? [];
  const current = b.settings?.version ?? 0;
  if (devices.length === 0) {
    return card(h('p', { class: 'muted' }, 'Belum ada HP yang terhubung ke cloud.'), button('Hubungkan HP', () => setTab('hp'), { kind: 'btn-ghost', iconName: 'phone' }));
  }
  return h('div', { class: 'list' },
    devices.map((d) => {
      const { ds, nodes } = deviceLines(d, current);
      return h('div', { class: `card dev-card ${ds.stale ? 'dev-card-stale' : ''}`.trim() }, h('p', { class: 'strong' }, d.label || 'HP presensi'), nodes);
    }),
  );
}

// ---------------------------------------------------------------- tab: Rekap

function renderRekap() {
  const v = $('view-rekap');
  const rk = state.rekap;
  const thisMonth = R.ymOf(R.localDateString());
  if (!rk.ym || rk.ym > thisMonth) rk.ym = thisMonth;
  const changeMonth = (n) => {
    const next = R.addMonths(rk.ym, n);
    if (next > thisMonth) return;
    rk.ym = next;
    rk.open = new Set();
    loadRekap();
  };
  const nav = h('div', { class: 'month-nav' },
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bulan sebelumnya', onclick: () => changeMonth(-1) }, icon('left')),
    h('h2', { class: 'month-label' }, R.monthLabel(rk.ym)),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bulan berikutnya', disabled: rk.ym >= thisMonth, onclick: () => changeMonth(1) }, icon('right')),
  );
  const ready = rk.data && rk.data.siteId === state.siteId && rk.data.ym === rk.ym;
  if (!ready) {
    v.replaceChildren(nav, rk.error ? errorCard(rk.error, () => loadRekap()) : loadingCard('Memuat rekap…'));
    return;
  }
  const payload = rk.data.payload;
  const s = settingsOf(payload);
  const today = R.localDateString();
  const employees = payload.employees ?? [];
  const records = payload.attendance ?? [];
  const ex = R.buildExports({ ym: rk.ym, today, siteId: state.siteId, employees, records, settings: s });
  const perDay = ex.recaps[0]?.deductionPerDay ?? R.deductionPerDay(R.bonusConfig(s), R.workDays(rk.ym, s.workDays, R.holidayDates(s)).length);
  const workDayCount = R.workDays(rk.ym, s.workDays, R.holidayDates(s)).length;
  const isCurrent = rk.ym === thisMonth;

  const nodes = [nav];
  if (rk.error) nodes.push(notice('bad', h('strong', null, 'Gagal memperbarui. '), rk.error));
  if (rk.loading) nodes.push(h('p', { class: 'muted small center' }, h('span', { class: 'spinner', 'aria-hidden': 'true' }), ' Memperbarui…'));
  nodes.push(
    h('div', { class: 'card card-accent' },
      h('p', { class: 'strong' }, `Uang rajin penuh ${R.rupiah(s.monthlyBonus)} · ${workDayCount}\u00a0hari\u00a0kerja`),
      h('p', null, `Potongan ${R.rupiah(perDay)} per hari telat${s.deductAbsent ? ' atau alpa.' : '. Alpa tidak dipotong.'}`),
      h('p', { class: 'muted small' }, `Jam masuk: ${R.rulesText(s)}.`),
      isCurrent ? h('p', { class: 'muted small' }, 'Bulan berjalan: angka masih sementara sampai akhir bulan.') : null,
    ),
  );
  if (ex.recaps.length === 0) {
    nodes.push(card(h('p', { class: 'muted' }, 'Belum ada karyawan aktif.')));
  }
  const s2 = s;
  nodes.push(h('div', { class: 'list' }, ex.recaps.map((r) => recapCard(r, records, s2, today))));
  nodes.push(exportCard(rk.ym, payload, s));
  nodes.push(h('p', { class: 'muted small center' }, 'Ketuk nama untuk melihat detail harian.'));
  v.replaceChildren(...nodes.filter(Boolean));
}

function recapCard(r, records, s, today) {
  const rk = state.rekap;
  const emp = r.employee;
  const NB = '\u00a0';
  const stats = [
    `Hadir${NB}${r.hadir}/${r.workDaysElapsed}`,
    `Tepat${NB}${r.tepat}`,
    `Telat${NB}${r.telat}`,
    `Alpa${NB}${r.alpa}`,
    r.izin + r.sakit > 0 ? `Izin/Sakit${NB}${r.izin + r.sakit}` : null,
    r.outsideLocation > 0 ? `Luar${NB}lokasi${NB}${r.outsideLocation}` : null,
  ].filter(Boolean).join(' · ');
  const days = R.employeeDays({ ym: rk.ym, today, employeeId: emp.id, records, workDayOfWeeks: s.workDays, holidays: R.holidayDates(s) });
  const details = h('details', {
    class: 'card recap-card', open: rk.open.has(emp.id),
    ontoggle: () => {
      if (details.open) rk.open.add(emp.id);
      else rk.open.delete(emp.id);
    },
  },
  h('summary', { class: 'recap-summary' },
    avatar(emp),
    h('div', { class: 'recap-main' }, h('span', { class: 'strong' }, R.employeeLabel(emp)), h('span', { class: 'muted small' }, stats)),
    h('div', { class: 'recap-money' },
      h('span', { class: `money ${r.totalDeduction === 0 ? 'money-ok' : 'money-bad'}` }, R.rupiah(r.bonus)),
      r.totalDeduction > 0
        ? h('span', { class: 'small money-bad' }, `−${R.rupiah(r.totalDeduction)}`)
        : r.isFullMonth ? h('span', { class: 'small money-ok' }, 'Full bulan ✓') : null,
    ),
  ),
  h('div', { class: 'day-list' },
    days.length === 0 ? h('p', { class: 'muted' }, 'Belum ada catatan bulan ini.') : null,
    days.map((d) => dayRow(d)),
  ));
  return details;
}

function dayRow(d) {
  if (d.alpa) {
    return h('div', { class: 'day-row' }, h('span', { class: 'day-date' }, R.shortDateLabel(d.date)), h('span', { class: 'day-time' }, '–'), pill('Alpa', 'bad-outline'));
  }
  const rec = d.record;
  const hadir = rec.status === R.STATUS.TEPAT || rec.status === R.STATUS.TELAT;
  const loc = R.locationBadge(rec);
  return h('div', { class: 'day-row' },
    h('span', { class: 'day-date' }, R.shortDateLabel(rec.date)),
    h('span', { class: 'day-time' }, String(rec.timeIn ?? '').slice(0, 5) || '–'),
    pill(rec.lateMinutes > 0 ? `Telat ${rec.lateMinutes} mnt` : { TEPAT: 'Tepat waktu', TELAT: 'Telat', IZIN: 'Izin', SAKIT: 'Sakit' }[rec.status] ?? String(rec.status), statusKind(rec.status)),
    hadir && !rec.inLocation ? pill('Luar lokasi', 'bad') : null,
    loc?.mapUrl ? h('a', { class: 'map-link', href: loc.mapUrl, target: '_blank', rel: 'noopener noreferrer', 'aria-label': 'Lihat lokasi di peta' }, icon('pin', 'ic ic-sm')) : null,
    rec.note ? h('span', { class: 'muted small day-note' }, rec.note) : null,
  );
}

function exportCard(ym, payload, s) {
  const build = () => R.buildExports({ ym, today: R.localDateString(), siteId: state.siteId, employees: payload.employees ?? [], records: payload.attendance ?? [], settings: s });
  const names = R.exportFileNames(ym);
  return h('div', { class: 'card' },
    h('p', { class: 'card-title' }, `Ekspor rekap ${R.monthLabel(ym)}`),
    h('div', { class: 'btn-col' },
      button('Salin teks untuk Claude', async () => {
        const md = build().markdown;
        if (await copyText(md)) toast('Rekap disalin. Tempel ke Claude.');
        else showTextForManualCopy('Salin rekap', md);
      }, { kind: 'btn-primary', iconName: 'copy' }),
      h('div', { class: 'btn-row wrap' },
        button('Detail CSV', () => downloadText(names.detail, build().detail, 'text/csv;charset=utf-8'), { kind: 'btn-ghost', iconName: 'download', attrs: { title: names.detail } }),
        button('Rekap CSV', () => downloadText(names.summary, build().summary, 'text/csv;charset=utf-8'), { kind: 'btn-ghost', iconName: 'download', attrs: { title: names.summary } }),
        button('Markdown', () => downloadText(names.markdown, build().markdown, 'text/markdown;charset=utf-8'), { kind: 'btn-ghost', iconName: 'download', attrs: { title: names.markdown } }),
      ),
    ),
    h('p', { class: 'muted small' },
      `Format sama dengan ekspor aplikasi Android: ${names.detail} (detail harian), ${names.summary} (rekap per karyawan), ${names.markdown} (siap tempel ke Claude). Cloud tidak menyimpan foto, jadi kolom foto di CSV detail kosong.`),
  );
}

// ---------------------------------------------------------------- tab: Karyawan

function renderKaryawan() {
  const v = $('view-karyawan');
  if (baseGate(v)) return;
  const b = state.base;
  const employees = b.employees ?? [];
  const sorted = [...employees].sort((a, c) => (a.active === c.active ? a.id - c.id : a.active ? -1 : 1));
  v.replaceChildren(
    ...[
      baseStatusNotice(),
      // Sebelum HP pertama kali sinkron, daftar karyawan harus berasal dari HP (nomor karyawan = nomor di HP).
      // Menambah di sini lebih dulu akan memberi nomor yang bentrok dengan karyawan di HP.
      sectionTitle(`Karyawan ${b.site?.name ?? ''}`.trim(),
        sorted.length === 0 ? null : button('Tambah', () => openEmployeeDialog(null), { kind: 'btn-primary btn-sm', iconName: 'plus' })),
      notice('info', `HP presensi mengambil perubahan di sini saat sinkron berikutnya. Karyawan nonaktif tidak tampil di HP dan tidak masuk rekap; datanya tetap tersimpan.`),
      sorted.length === 0
        ? card(h('p', { class: 'muted' }, 'Belum ada karyawan di cloud. Hubungkan HP presensi dulu (tab HP): daftar karyawan di HP dikirim otomatis saat HP pertama kali sinkron. Setelah itu karyawan bisa ditambah dan diubah di sini.'))
        : h('div', { class: 'list' },
          sorted.map((emp) =>
            h('div', { class: `card emp-card ${emp.active ? '' : 'emp-inactive'}`.trim() },
              avatar(emp),
              h('div', { class: 'emp-main' },
                h('div', { class: 'emp-name' }, h('span', { class: 'strong' }, emp.name),
                  h('span', { class: 'muted small' }, h('span', { class: 'nowrap' }, `No. ${emp.id}`), ' · ',
                    h('span', { class: 'nowrap' }, genderLabel(emp.gender)), emp.role ? [' · ', emp.role] : null)),
                h('div', null, pill(emp.active ? 'Aktif' : 'Nonaktif', emp.active ? 'ok' : 'neutral')),
              ),
              button('Ubah', () => openEmployeeDialog(emp), { kind: 'btn-ghost btn-sm', attrs: { 'aria-label': `Ubah ${emp.name}` } }),
            ),
          ),
        ),
    ].filter(Boolean),
  );
}

function openEmployeeDialog(emp) {
  const isNew = !emp;
  if (isNew && (state.base?.employees ?? []).length === 0) {
    toast('Hubungkan HP presensi dulu: daftar karyawan dikirim dari HP saat pertama kali sinkron.', { kind: 'bad' });
    return;
  }
  const name = h('input', { id: 'emp-name', type: 'text', maxlength: '60', autocomplete: 'off', value: emp?.name ?? '', required: true });
  const role = h('input', { id: 'emp-role', type: 'text', maxlength: '40', autocomplete: 'off', value: emp?.role ?? '', placeholder: 'mis. Laundry, Kos, Kasir' });
  const gP = h('input', { type: 'radio', name: 'emp-gender', id: 'emp-g-p', value: 'P', checked: (emp?.gender ?? 'P') === 'P' });
  const gL = h('input', { type: 'radio', name: 'emp-gender', id: 'emp-g-l', value: 'L', checked: emp?.gender === 'L' });
  const active = h('input', { type: 'checkbox', id: 'emp-active', checked: emp ? Boolean(emp.active) : true });
  const err = h('p', { class: 'form-error', role: 'alert' });
  let busy = false;
  const save = async () => {
    if (busy) return;
    const parsed = R.employeeFromForm({ id: emp?.id, name: name.value, role: role.value, gender: gP.checked ? 'P' : gL.checked ? 'L' : '', active: active.checked });
    if (parsed.error) {
      err.textContent = parsed.error;
      ({ name, role }[parsed.field] ?? gP).focus();
      return;
    }
    busy = true;
    saveBtn.disabled = true;
    err.textContent = '';
    try {
      const saved = await api.ownerSaveEmployee(state.key, state.siteId, parsed.employee);
      closeDialog();
      toast(`${saved?.name ?? parsed.employee.name} disimpan. HP presensi mengambil perubahan ini saat sinkron berikutnya.`);
      invalidateRekap();
      loadBase({ quiet: true });
    } catch (e) {
      if (handleAuth(e)) {
        closeDialog();
        return;
      }
      err.textContent = e.message;
    } finally {
      busy = false;
      saveBtn.disabled = false;
    }
  };
  const form = h('form', { class: 'dialog-form', novalidate: true, onsubmit: (e) => { e.preventDefault(); save(); } },
    h('label', { class: 'field-label', for: 'emp-name' }, 'Nama'),
    name,
    h('label', { class: 'field-label', for: 'emp-role' }, 'Bagian (opsional)'),
    role,
    h('p', { class: 'field-hint' }, 'Tampil sebagai "Nama · Bagian" di rekap, mis. "Inul · Laundry".'),
    h('fieldset', { class: 'radio-row' }, h('legend', { class: 'field-label' }, 'Gender (menentukan jam masuk)'),
      h('label', { for: 'emp-g-p', class: 'check-row' }, gP, h('span', null, 'Perempuan')),
      h('label', { for: 'emp-g-l', class: 'check-row' }, gL, h('span', null, 'Laki-laki')),
    ),
    h('label', { class: 'check-row', for: 'emp-active' }, active, h('span', null, 'Aktif (tampil di HP presensi dan rekap)')),
    err,
    h('button', { type: 'submit', class: 'offscreen', tabindex: '-1', 'aria-hidden': 'true' }),
  );
  const saveBtn = button(isNew ? 'Tambah' : 'Simpan', save, { kind: 'btn-primary' });
  openDialog(isNew ? 'Tambah karyawan' : `Ubah ${emp.name}`, form, [button('Batal', closeDialog, { kind: 'btn-ghost' }), saveBtn]);
  name.focus();
}

// ---------------------------------------------------------------- tab: Pengaturan

function renderPengaturan() {
  const v = $('view-pengaturan');
  if (baseGate(v)) return;
  const version = state.base.settings?.version ?? 0;
  const f = state.form;
  if (!f || f.siteId !== state.siteId || (!f.dirty && f.version !== version)) {
    state.form = createSettingsForm();
  }
  const form = state.form;
  form.refreshInfo();
  // Pasang sekali; render ulang berikutnya tidak memindahkan form (fokus & ketikan tetap).
  if (form.root.parentNode !== v) v.replaceChildren(form.noticeSlot, form.root);
  const n = baseStatusNotice();
  form.noticeSlot.replaceChildren(...(n ? [n] : []));
}

function createSettingsForm() {
  const b = state.base;
  const site = currentSite();
  const words = siteWords();
  const version = b.settings?.version ?? 0;
  const rawData = b.settings?.data && typeof b.settings.data === 'object' ? b.settings.data : {};
  const defaults = b.site?.defaultSettings ?? {};
  const loaded = settingsOf(b);
  const f = { siteId: state.siteId, version, dirty: false, busy: false, holidays: [], root: null, noticeSlot: h('div'), refreshInfo: () => {} };

  const mark = () => {
    if (!f.dirty) {
      f.dirty = true;
      saveBar.hidden = false;
    }
    updateHints();
  };
  const input = (id, attrs = {}) => h('input', { id, name: id, autocomplete: 'off', ...attrs });
  const field = (id, label, control, hint = null) =>
    h('div', { class: 'field' }, h('label', { class: 'field-label', for: id }, label), control, hint ? h('p', { class: 'field-hint', id: `${id}-hint` }, hint) : null);
  const toggle = (id, label, hint) => {
    const cb = input(id, { type: 'checkbox', role: 'switch' });
    return { cb, node: h('label', { class: 'switch-row', for: id }, h('span', { class: 'switch-text' }, h('span', { class: 'strong' }, label), hint ? h('span', { class: 'muted small' }, hint) : null), cb) };
  };

  // --- kontrol ---
  const wsF = input('workStartFemale', { type: 'time', required: true });
  const tolF = input('toleranceFemale', { type: 'number', inputmode: 'numeric', min: '0', max: '240', step: '1' });
  const wsM = input('workStartMale', { type: 'time', required: true });
  const tolM = input('toleranceMale', { type: 'number', inputmode: 'numeric', min: '0', max: '240', step: '1' });
  const dayBoxes = R.DAY_SHORT.map((label, i) => {
    const cb = h('input', { type: 'checkbox', value: String(i + 1), id: `wd-${i + 1}` });
    return { cb, node: h('label', { class: 'day-chip', for: `wd-${i + 1}` }, cb, h('span', null, label)) };
  });
  const bonus = input('monthlyBonus', { type: 'text', inputmode: 'numeric' });
  const ded = input('lateDeduction', { type: 'text', inputmode: 'numeric' });
  const dedHint = h('p', { class: 'field-hint' });
  const absent = toggle('deductAbsent', 'Potong juga hari alpa', 'Alpa = tidak absen di hari kerja tanpa izin/sakit.');
  const bio = toggle('requireBiometric', 'Wajib biometrik', `Aktif: sidik jari / wajah / PIN ${words.device} diminta sebelum selfie. Matikan hanya jika ${words.device} tidak punya kunci layar; selfie berstempel waktu + GPS tetap menjadi bukti utama.`);
  const loc = input('location', { type: 'text', inputmode: 'text', placeholder: 'mis. -6.200000, 106.816666', spellcheck: 'false' });
  const mapLink = h('a', { class: 'map-link', target: '_blank', rel: 'noopener noreferrer', hidden: true }, 'Lihat di Google Maps', icon('open', 'ic ic-sm'));
  const locHint = h('p', { class: 'field-hint' });
  const gpsBtn = button('Pakai lokasi saya sekarang', () => useMyLocation(), { kind: 'btn-ghost', iconName: 'gps' });
  const radius = input('radiusMeters', { type: 'number', inputmode: 'numeric', min: '10', max: '100000', step: '1' });
  const reqLoc = toggle('requireLocation', `Wajib di lokasi ${words.place}`, 'Aktif: absen ditolak jika di luar radius atau GPS tidak didapat. Nonaktif: tetap tercatat tapi ditandai "di luar lokasi".');
  const keep = input('photoRetentionDays', { type: 'number', inputmode: 'numeric', min: '0', max: '3650', step: '1' });
  const keepHint = h('p', { class: 'field-hint strong-hint' });
  const holDate = input('holidayDate', { type: 'date' });
  const holName = input('holidayName', { type: 'text', maxlength: '60', placeholder: 'mis. Natal' });
  const holList = h('div', { class: 'holiday-list' });
  const formError = h('p', { class: 'form-error', role: 'alert' });
  const conflictBox = h('div');
  const infoBox = h('div');
  const saveBtn = button('Simpan pengaturan', null, { kind: 'btn-primary btn-block', type: 'submit' });
  const saveBtn2 = button('Simpan', () => save(), { kind: 'btn-primary' });
  const saveBar = h('div', { class: 'save-bar', hidden: true }, h('span', null, 'Ada perubahan yang belum disimpan'), saveBtn2);
  const controls = { workStartFemale: wsF, toleranceFemale: tolF, workStartMale: wsM, toleranceMale: tolM, monthlyBonus: bonus, lateDeduction: ded, location: loc, radiusMeters: radius, photoRetentionDays: keep, holidays: holDate, workDays: dayBoxes[0].cb };

  const activeNames = (g) => (b.employees ?? []).filter((e) => e.active && e.gender === g).map((e) => e.name);
  const genderTitle = (title, g) => {
    const names = activeNames(g);
    return names.length ? `${title} (${names.join(', ')})` : `${title} (belum ada karyawan)`;
  };

  function fill(s) {
    wsF.value = s.workStartFemale;
    tolF.value = String(s.toleranceFemale);
    wsM.value = s.workStartMale;
    tolM.value = String(s.toleranceMale);
    for (const d of dayBoxes) d.cb.checked = s.workDays.includes(Number(d.cb.value));
    bonus.value = String(s.monthlyBonus);
    ded.value = String(s.lateDeduction);
    absent.cb.checked = s.deductAbsent;
    bio.cb.checked = s.requireBiometric;
    loc.value = R.hasStoreLocation(s) ? R.formatCoord(s.storeLat, s.storeLon) : '';
    radius.value = String(s.radiusMeters);
    reqLoc.cb.checked = s.requireLocation;
    keep.value = String(s.photoRetentionDays);
    f.holidays = s.holidays.map((x) => ({ date: x.date, name: x.name }));
    renderHolidays();
    updateHints();
  }

  function read() {
    return {
      workStartFemale: wsF.value, toleranceFemale: tolF.value, workStartMale: wsM.value, toleranceMale: tolM.value,
      monthlyBonus: bonus.value, lateDeduction: ded.value, deductAbsent: absent.cb.checked,
      workDays: dayBoxes.filter((d) => d.cb.checked).map((d) => Number(d.cb.value)),
      location: loc.value, radiusMeters: radius.value, requireLocation: reqLoc.cb.checked, requireBiometric: bio.cb.checked,
      photoRetentionDays: keep.value, holidays: f.holidays,
    };
  }

  function updateHints() {
    // Potongan otomatis bulan ini, dihitung dari isi form.
    const ym = R.ymOf(R.localDateString());
    const days = R.workDays(ym, dayBoxes.filter((d) => d.cb.checked).map((d) => Number(d.cb.value)), new Set(f.holidays.map((x) => x.date)));
    const bon = R.parseRupiahText(bonus.value);
    const d = R.parseRupiahText(ded.value);
    if (d != null && d > 0) dedHint.textContent = `Potongan tetap ${R.rupiah(d)} per hari telat.`;
    else if (bon != null) dedHint.textContent = `0 = otomatis: uang rajin ÷ jumlah hari kerja bulan itu. ${R.monthLabel(ym)}: ${days.length} hari kerja → ${R.rupiah(R.deductionPerDay({ monthlyBonus: bon, lateDeductionOverride: 0 }, days.length))} per hari.`;
    else dedHint.textContent = '0 = otomatis: uang rajin ÷ jumlah hari kerja bulan itu.';
    // Lokasi.
    const ll = loc.value.trim() === '' ? null : R.parseLocationText(loc.value);
    if (ll) {
      mapLink.href = R.mapsUrl(ll[0], ll[1]);
      mapLink.hidden = false;
      locHint.textContent = `Titik ${words.place}: ${R.formatCoord(ll[0], ll[1])}.`;
    } else {
      mapLink.removeAttribute('href');
      mapLink.hidden = true;
      locHint.textContent = loc.value.trim() === '' ? 'Kosong = pengecekan lokasi tidak aktif.' : 'Koordinat belum terbaca. Format: "lat, lon", contoh -6.200000, 106.816666.';
    }
    // Simpan foto.
    const k = R.parseIntText(keep.value);
    if (k === 0) keepHint.textContent = 'Foto selfie di HP disimpan selamanya (tidak dihapus otomatis); data presensi tetap.';
    else if (k != null && k >= 7 && k <= 3650) keepHint.textContent = `Foto selfie di HP dihapus otomatis setelah ${k} hari; data presensi tetap.`;
    else keepHint.textContent = 'Isi 0 (simpan selamanya) atau 7–3650 hari.';
  }

  function renderHolidays() {
    const today = R.localDateString();
    const sorted = [...f.holidays].sort((x, y) => (x.date < y.date ? -1 : x.date > y.date ? 1 : 0));
    holList.replaceChildren(
      sorted.length === 0
        ? h('p', { class: 'muted small' }, 'Belum ada hari libur.')
        : h('ul', { class: 'holidays' },
          sorted.map((x) =>
            h('li', { class: x.date < today ? 'past' : '' },
              h('span', { class: 'hol-date' }, R.isValidDate(x.date) ? `${R.shortDateLabel(x.date).slice(0, 3)}, ${R.mediumDateLabel(x.date)}` : x.date),
              h('span', { class: 'hol-name' }, x.name || 'Libur'),
              h('button', {
                type: 'button', class: 'icon-btn icon-btn-sm', 'aria-label': `Hapus hari libur ${x.date}`,
                onclick: () => {
                  f.holidays = f.holidays.filter((y) => y.date !== x.date);
                  renderHolidays();
                  mark();
                },
              }, icon('close')),
            ),
          ),
        ),
    );
  }

  function addHoliday() {
    const date = holDate.value.trim();
    if (!R.isValidDate(date)) {
      showError('Tanggal libur harus diisi (yyyy-mm-dd), contoh 2026-12-25.', 'holidays');
      return;
    }
    const name = holName.value.trim() || 'Libur';
    if ([...name].length > 60) {
      showError('Nama hari libur maks 60 huruf.', 'holidays');
      return;
    }
    f.holidays = [...f.holidays.filter((x) => x.date !== date), { date, name }];
    holDate.value = '';
    holName.value = '';
    formError.textContent = '';
    renderHolidays();
    mark();
  }

  function showError(message, field) {
    formError.textContent = message;
    for (const c of Object.values(controls)) c.classList.remove('invalid');
    const c = controls[field];
    if (c) {
      c.classList.add('invalid');
      c.focus({ preventScroll: true });
      c.scrollIntoView({ block: 'center', behavior: 'smooth' });
    } else {
      formError.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }

  function useMyLocation() {
    if (!('geolocation' in navigator)) {
      toast('Browser ini tidak bisa membaca lokasi.', { kind: 'bad' });
      return;
    }
    gpsBtn.disabled = true;
    gpsBtn.lastChild.textContent = 'Mencari posisi GPS…';
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        gpsBtn.disabled = false;
        gpsBtn.lastChild.textContent = 'Pakai lokasi saya sekarang';
        loc.value = R.formatCoord(pos.coords.latitude, pos.coords.longitude);
        mark();
        toast(`Posisi diambil (akurasi ±${Math.round(pos.coords.accuracy)} m). Tekan Simpan pengaturan.`);
      },
      (err) => {
        gpsBtn.disabled = false;
        gpsBtn.lastChild.textContent = 'Pakai lokasi saya sekarang';
        const msg = err.code === 1
          ? 'Izin lokasi ditolak. Izinkan lokasi untuk situs ini di pengaturan browser.'
          : err.code === 3 ? 'Waktu habis mencari GPS. Coba lagi di dekat jendela / luar ruangan.' : 'Posisi tidak didapat. Nyalakan Lokasi HP lalu coba lagi.';
        toast(msg, { kind: 'bad' });
      },
      { enableHighAccuracy: true, timeout: 30000, maximumAge: 0 },
    );
  }

  async function save() {
    if (f.busy) return;
    formError.textContent = '';
    for (const c of Object.values(controls)) c.classList.remove('invalid');
    const parsed = R.settingsFromForm(read(), rawData);
    if (parsed.error) {
      showError(parsed.error, parsed.field);
      return;
    }
    f.busy = true;
    saveBtn.disabled = true;
    saveBtn2.disabled = true;
    try {
      const res = await api.ownerSaveSettings(state.key, f.siteId, parsed.data, f.version);
      if (res && res.ok === true) {
        f.dirty = false;
        state.pendingConflict = null;
        toast(`Pengaturan disimpan (versi ${res.version}). HP presensi menerapkannya saat sinkron berikutnya.`);
        state.form = null;
        invalidateRekap();
        await loadBase({ quiet: true });
      } else {
        // Versi di cloud sudah berubah (HP atau perangkat lain menyimpan lebih dulu): muat ulang, simpan ketikan owner.
        state.pendingConflict = { siteId: f.siteId, mine: parsed.data, triedVersion: f.version, cloudVersion: res?.version ?? null };
        f.dirty = false;
        state.form = null;
        invalidateRekap();
        await loadBase({ quiet: true });
        toast('Pengaturan sudah diubah di tempat lain. Versi terbaru dimuat; nilai Anda masih bisa dipakai lagi.', { kind: 'bad' });
      }
    } catch (e) {
      if (handleAuth(e)) return;
      showError(e.message, '');
      toast(e.message, { kind: 'bad' });
    } finally {
      f.busy = false;
      saveBtn.disabled = false;
      saveBtn2.disabled = false;
    }
  }

  function renderConflict() {
    const pc = state.pendingConflict;
    if (!pc || pc.siteId !== f.siteId) {
      conflictBox.replaceChildren();
      return;
    }
    const mineS = R.normalizeSettings(defaults, pc.mine);
    const cloudS = loaded;
    const keys = R.settingsDiff(mineS, cloudS);
    const dismiss = () => {
      state.pendingConflict = null;
      conflictBox.replaceChildren();
    };
    conflictBox.replaceChildren(
      h('div', { class: 'card card-warn', role: 'alert' },
        h('p', { class: 'card-title' }, 'Pengaturan sudah diubah dari tempat lain'),
        h('p', null, `Sebelum Anda menyimpan, ${b.settings?.updatedBy ? `"${b.settings.updatedBy}"` : 'HP presensi atau perangkat lain'} sudah menyimpan pengaturan baru. Form di bawah sekarang berisi versi terbaru dari cloud${version ? ` (versi ${version})` : ''}.`),
        keys.length === 0
          ? h('p', { class: 'muted' }, 'Isi yang Anda ketik sama dengan versi terbaru, jadi tidak perlu disimpan lagi.')
          : h('div', null,
            h('p', null, 'Nilai yang Anda ketik (belum tersimpan):'),
            h('ul', { class: 'diff-list' },
              keys.map((k) => h('li', null, h('span', { class: 'strong' }, `${R.SETTINGS_LABELS[k]}: `), `Anda ${R.settingValueText(k, mineS)}`, h('span', { class: 'muted' }, ` · cloud ${R.settingValueText(k, cloudS)}`))),
            ),
          ),
        h('div', { class: 'btn-row wrap' },
          keys.length ? button('Pakai lagi nilai saya', () => {
            fill(mineS);
            mark();
            dismiss();
            toast('Nilai Anda dimasukkan lagi ke form. Periksa, lalu tekan Simpan pengaturan.');
          }, { kind: 'btn-primary' }) : null,
          button(keys.length ? 'Buang nilai saya' : 'Tutup', dismiss, { kind: 'btn-ghost' }),
        ),
      ),
    );
  }

  f.refreshInfo = () => {
    const cur = state.base;
    const ver = cur?.settings?.version ?? 0;
    const items = [];
    if (!cur?.settings) {
      items.push(notice('warn', h('strong', null, 'Pengaturan belum pernah dikirim HP ke cloud. '),
        `Form diisi nilai bawaan ${site.name}. Kalau disimpan sekarang, nilai ini akan diterima ${words.device} saat sinkron dan menimpa pengaturan di HP (termasuk titik lokasi). Sebaiknya tunggu HP sinkron dulu, kecuali Anda memang ingin mengatur dari sini.`));
    } else {
      const by = cur.settings.updatedBy === 'owner' ? 'owner (dashboard)' : cur.settings.updatedBy || '-';
      const at = R.parseTimestamp(cur.settings.updatedAt);
      items.push(h('p', { class: 'muted small' }, `Versi pengaturan ${ver} · terakhir diubah ${by}${at != null ? `, ${R.dateTimeLabel(at)}` : ''}.`));
      const waiting = (cur.devices ?? []).filter((d) => (d.settingsVersion ?? 0) < ver);
      for (const d of waiting) {
        items.push(notice('info', `${d.label || 'HP presensi'} belum menerapkan versi ${ver} (${d.settingsVersion ? `masih versi ${d.settingsVersion}` : 'belum pernah sinkron'}). Diterapkan otomatis saat HP sinkron.`));
      }
    }
    if (f.dirty && ver !== f.version) {
      items.push(notice('bad', `Pengaturan di cloud berubah (versi ${ver}) sejak form ini dibuka. Menyimpan akan ditolak; Anda akan ditawari memakai lagi nilai yang Anda ketik.`));
    }
    infoBox.replaceChildren(...items);
    renderConflict();
  };

  const form = h('form', {
    class: 'settings-form', novalidate: true,
    onsubmit: (e) => {
      e.preventDefault();
      save();
    },
    oninput: (e) => {
      if (e.target === holDate || e.target === holName) return;
      mark();
    },
    onchange: (e) => {
      if (e.target === holDate || e.target === holName) return;
      mark();
    },
  },
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, 'Aturan jam masuk'),
    h('p', { class: 'muted small' }, 'Datang lewat dari jam masuk + toleransi = telat. Menit telat dihitung dari jam masuk.'),
    h('p', { class: 'group-label' }, genderTitle('Perempuan', 'P')),
    h('div', { class: 'grid-2' }, field('workStartFemale', 'Jam masuk', wsF), field('toleranceFemale', 'Toleransi (mnt)', tolF)),
    h('p', { class: 'group-label' }, genderTitle('Laki-laki', 'L')),
    h('div', { class: 'grid-2' }, field('workStartMale', 'Jam masuk', wsM), field('toleranceMale', 'Toleransi (mnt)', tolM)),
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, 'Hari kerja'),
    h('div', { class: 'day-chips', role: 'group', 'aria-label': 'Hari kerja' }, dayBoxes.map((d) => d.node)),
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, 'Uang rajin'),
    field('monthlyBonus', 'Uang rajin penuh per bulan (Rp)', bonus),
    h('div', { class: 'field' }, h('label', { class: 'field-label', for: 'lateDeduction' }, 'Potongan per hari telat (Rp)'), ded, dedHint),
    absent.node,
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, 'Verifikasi sebelum selfie'),
    bio.node,
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, `Lokasi ${words.placeFull}`),
    h('p', { class: 'muted small' }, `Selfie hanya sah bila HP berada dalam radius ini dari titik ${words.place}. Tekan "Pakai lokasi saya sekarang" hanya saat Anda berdiri di dalam ${words.place}, atau tempel koordinat dari Google Maps (tekan lama lokasi → salin angka di atas).`),
    h('div', { class: 'field' }, h('label', { class: 'field-label', for: 'location' }, `Koordinat ${words.place} (lat, lon)`), loc, locHint, mapLink),
    h('div', { class: 'btn-row' }, gpsBtn),
    field('radiusMeters', 'Radius toleransi (meter)', radius, `Akurasi GPS ikut diperhitungkan. ${words.radiusHint}`.trim()),
    reqLoc.node,
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, 'Foto selfie di HP'),
    h('div', { class: 'field' }, h('label', { class: 'field-label', for: 'photoRetentionDays' }, 'Hapus foto otomatis setelah (hari)'), keep, keepHint),
    h('p', { class: 'muted small' }, `Untuk mengosongkan memori ${words.device}. 0 = simpan selamanya, selain itu 7–3650 hari (bawaan 60). Foto tidak pernah dikirim ke cloud; cloud hanya menyimpan jam, status, dan koordinat.`),
  ),
  h('section', { class: 'card' },
    h('h2', { class: 'card-title' }, `Hari libur ${words.place}`),
    h('p', { class: 'muted small' }, 'Tanggal di sini tidak dihitung sebagai hari kerja (tidak ada alpa, tidak dipotong).'),
    holList,
    h('div', { class: 'grid-2 holiday-add' }, field('holidayDate', 'Tanggal', holDate), field('holidayName', 'Nama', holName)),
    h('div', { class: 'btn-row' }, button('Tambah hari libur', addHoliday, { kind: 'btn-ghost', iconName: 'plus' })),
  ),
  formError,
  saveBtn,
  h('p', { class: 'muted small center' }, `Setelah disimpan, ${words.device} menerapkan pengaturan ini saat sinkron berikutnya.`),
  saveBar,
  );
  holName.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      addHoliday();
    }
  });
  holDate.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      addHoliday();
    }
  });

  f.root = h('div', { class: 'settings' }, infoBox, conflictBox, form);
  fill(loaded);
  f.dirty = false;
  saveBar.hidden = true;
  return f;
}

// ---------------------------------------------------------------- tab: HP

let pairingTick = 0;
let pairingPoll = 0;

function stopPairingTimers() {
  clearInterval(pairingTick);
  clearInterval(pairingPoll);
  pairingTick = 0;
  pairingPoll = 0;
}

function startPairingTimers() {
  stopPairingTimers();
  pairingTick = setInterval(updateCountdown, 1000);
  pairingPoll = setInterval(() => {
    if (document.visibilityState === 'visible' && state.tab === 'hp' && state.pairing && !state.baseLoading) loadBase({ quiet: true });
  }, PAIRING_POLL_MS);
  updateCountdown();
}

function pairingLeftMs() {
  return state.pairing ? state.pairing.expiresMs - serverNow() : 0;
}

function updateCountdown() {
  const el = $('pair-countdown');
  if (!state.pairing || !el) return;
  const left = pairingLeftMs();
  el.textContent = left > 0 ? `Berlaku ${R.countdownText(left)}` : 'Kode kedaluwarsa. Buat kode baru.';
  el.classList.toggle('expired', left <= 0);
  $('pair-code')?.classList.toggle('expired', left <= 0);
  if (left <= 0) stopPairingTimers();
}

function checkPairingDone() {
  const p = state.pairing;
  if (!p || p.siteId !== state.siteId || !state.base) return;
  const fresh = (state.base.devices ?? []).filter((d) => !p.knownIds.has(d.id));
  if (fresh.length > 0) {
    state.pairing = null;
    stopPairingTimers();
    toast(`HP baru terhubung: ${fresh.map((d) => d.label || 'HP presensi').join(', ')}.`);
  }
}

function renderHp() {
  const v = $('view-hp');
  if (baseGate(v)) return;
  const b = state.base;
  const words = siteWords();
  const current = b.settings?.version ?? 0;
  const devices = b.devices ?? [];
  v.replaceChildren(
    ...[
      baseStatusNotice(),
      pairingCard(words),
      sectionTitle(`HP terhubung (${devices.length})`),
      devices.length === 0
        ? card(h('p', { class: 'muted' }, `Belum ada HP yang terhubung ke cloud ${b.site?.name ?? ''}.`))
        : h('div', { class: 'list' },
          devices.map((d) => {
            const { ds, nodes } = deviceLines(d, current);
            const created = R.parseTimestamp(d.createdAt);
            return h('div', { class: `card dev-card ${ds.stale ? 'dev-card-stale' : ''}`.trim() },
              h('div', { class: 'dev-head' }, h('p', { class: 'strong' }, d.label || 'HP presensi'),
                button('Putuskan', () => revokeDevice(d), { kind: 'btn-danger-ghost btn-sm', attrs: { 'aria-label': `Putuskan ${d.label || 'HP presensi'}` } })),
              nodes,
              h('p', { class: 'muted small' },
                [
                  ds.appliedVersion > 0 ? `Pengaturan versi ${ds.appliedVersion}${ds.waitingSync ? '' : current > 0 ? ' (terbaru)' : ''}` : 'Pengaturan belum diterapkan',
                  created != null ? `terhubung sejak ${R.dateTimeLabel(created)}` : null,
                ].filter(Boolean).join(' · '),
              ),
            );
          }),
        ),
    ].filter(Boolean),
  );
  if (state.pairing) startPairingTimers();
}

function pairingCard(words) {
  const p = state.pairing && state.pairing.siteId === state.siteId ? state.pairing : null;
  if (p) {
    const left = pairingLeftMs();
    return h('div', { class: 'card pair-card' },
      h('p', { class: 'card-title' }, 'Kode untuk HP baru'),
      h('p', { id: 'pair-code', class: `pair-code ${left <= 0 ? 'expired' : ''}`.trim(), 'aria-live': 'polite' }, p.code),
      h('p', { id: 'pair-countdown', class: `pair-countdown ${left <= 0 ? 'expired' : ''}`.trim() }, left > 0 ? `Berlaku ${R.countdownText(left)}` : 'Kode kedaluwarsa. Buat kode baru.'),
      h('ol', { class: 'steps' },
        h('li', null, `Buka aplikasi ${words.appTitle} di ${words.device}.`),
        h('li', null, 'Buka tab Pengaturan, lalu masukkan PIN owner.'),
        h('li', null, 'Gulir ke bagian "Dashboard owner (cloud)".'),
        h('li', null, 'Isi kode di atas di kolom "Kode dari dashboard", lalu tekan Hubungkan.'),
      ),
      h('p', { class: 'muted small' }, `Kode berlaku 15 menit, sekali pakai, dan hanya untuk aplikasi ${words.appTitle}. Halaman ini memberi tahu saat HP sudah terhubung.`),
      h('div', { class: 'btn-row wrap' },
        button('Salin kode', async () => {
          if (await copyText(p.code)) toast('Kode disalin.');
          else showTextForManualCopy('Salin kode', p.code);
        }, { kind: 'btn-ghost', iconName: 'copy' }),
        button('Buat kode baru', () => createPairingCode(), { kind: 'btn-ghost' }),
        button('Selesai', () => {
          state.pairing = null;
          stopPairingTimers();
          renderHp();
        }, { kind: 'btn-ghost' }),
      ),
    );
  }
  const err = h('p', { class: 'form-error', role: 'alert' });
  return h('form', {
    class: 'card', novalidate: true,
    onsubmit: (e) => {
      e.preventDefault();
      createPairingCode(err);
    },
  },
  h('p', { class: 'card-title' }, 'Hubungkan HP baru'),
  h('p', { class: 'muted small' }, `Buat kode 8 huruf, lalu masukkan di ${words.device}. HP itu kemudian mengirim presensi (tanpa foto) ke cloud dan menerima pengaturan dari dashboard ini.`),
  h('p', { class: 'muted small' }, 'Nama HP (mis. "HP Laundry") diisi di HP itu saat menghubungkan, dan tampil di daftar HP di atas.'),
  err,
  h('div', { class: 'btn-row' }, button('Buat kode', null, { kind: 'btn-primary', type: 'submit', iconName: 'phone' })),
  );
}

async function createPairingCode(errEl = null) {
  const site = state.siteId;
  try {
    // Label kosong: nama HP yang dipakai adalah yang diisi di HP saat menghubungkan.
    const res = await api.ownerCreatePairingCode(state.key, site, '');
    if (site !== state.siteId) return;
    const exp = R.parseTimestamp(res?.expiresAt) ?? serverNow() + 15 * 60 * 1000;
    state.pairing = { siteId: site, code: String(res?.code ?? ''), expiresMs: exp, knownIds: new Set((state.base?.devices ?? []).map((d) => d.id)) };
    renderHp();
    startPairingTimers();
  } catch (e) {
    if (handleAuth(e)) return;
    if (errEl) errEl.textContent = e.message;
    toast(e.message, { kind: 'bad' });
  }
}

async function revokeDevice(d) {
  const name = d.label || 'HP presensi';
  const ok = await confirmDialog({
    title: `Putuskan ${name}?`,
    message: `${name} tidak bisa sinkron lagi (kuncinya langsung ditolak) sampai dihubungkan ulang dengan kode baru. Presensi yang sudah ada di cloud tetap tersimpan; presensi di HP yang belum terkirim tidak akan terkirim.`,
    confirmLabel: 'Putuskan',
    danger: true,
  });
  if (!ok) return;
  try {
    const res = await api.ownerRevokeDevice(state.key, d.id);
    toast(res === true ? `${name} diputus.` : `${name} sudah tidak terhubung.`);
    loadBase({ quiet: true });
  } catch (e) {
    if (handleAuth(e)) return;
    toast(e.message, { kind: 'bad' });
  }
}

// ---------------------------------------------------------------- service worker (opsional)

function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  let hasController = Boolean(navigator.serviceWorker.controller);
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (hasController) {
      toast('Dashboard diperbarui.', { action: { label: 'Muat ulang', onClick: () => location.reload() } });
    }
    hasController = true;
  });
  navigator.serviceWorker
    .register('./sw.js')
    .then((reg) => {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') reg.update().catch(() => {});
      });
    })
    .catch(() => {
      /* tanpa service worker dashboard tetap jalan (hanya tidak bisa dibuka offline) */
    });
}

// ---------------------------------------------------------------- mulai

function wire() {
  for (const b of document.querySelectorAll('#tabs .tab')) {
    b.addEventListener('click', () => setTab(b.dataset.tab));
  }
  $('btn-refresh').addEventListener('click', refreshCurrent);
  $('btn-logout').addEventListener('click', logout);
  setInterval(() => {
    if (!state.key || document.visibilityState !== 'visible') return;
    if (state.tab === 'today' && isBaseStale()) loadBase({ quiet: true });
    else if (state.tab === 'today') renderToday(); // "online N menit lalu" & "Belum absen" merah tetap segar
    $('today-label').textContent = R.longDateLabel(R.localDateString());
  }, 15000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || !state.key) return;
    if (state.tab === 'today' && isBaseStale()) loadBase({ quiet: true });
  });
  // Tautan #k=... dibuka di tab yang sudah terbuka: browser tidak memuat ulang halaman, jadi tangani di sini.
  window.addEventListener('hashchange', () => {
    const k = readKeyFromFragment();
    if (k) signIn(k, { announce: true });
    else if (!state.key && state.loginError) showLogin();
  });
  window.addEventListener('online', () => {
    if (state.key && (state.baseError || state.baseOffline)) refreshCurrent();
  });
  window.addEventListener('beforeunload', (e) => {
    if (state.form?.dirty) {
      e.preventDefault();
      e.returnValue = '';
    }
  });
}

function boot() {
  wire();
  registerServiceWorker();
  const fromLink = readKeyFromFragment();
  const stored = R.normalizeOwnerKey(store.get(LS.key));
  const key = fromLink ?? stored;
  if (key) signIn(key, { announce: Boolean(fromLink) });
  else showLogin();
}

boot();
