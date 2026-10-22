/**
 * Klien RPC Supabase untuk dashboard owner (kontrak: docs/CLOUD_API.md).
 * Semua akses lewat POST {URL}/rest/v1/rpc/<fungsi>; tiap fungsi memeriksa kunci owner sendiri.
 * Kunci owner hanya dikirim di BADAN permintaan (JSON), tidak pernah di URL, dan tidak pernah dicatat ke konsol.
 */

export const SUPABASE_URL = 'https://yqgcovruqtfoamjynuyf.supabase.co';

/**
 * Kunci "publishable" Supabase. MEMANG PUBLIK (by design): boleh ada di halaman web/aplikasi, bukan rahasia.
 * Kunci ini hanya membuka RPC yang di-grant ke peran anon; tabel tidak bisa dibaca langsung, dan setiap RPC owner
 * menolak permintaan tanpa kunci owner yang benar.
 */
export const PUBLISHABLE_KEY = 'sb_publishable_jhA_daI9x0x0q4Bi3-uesQ_i0qlQm3f';

const TIMEOUT_MS = 25000;

/** Kesalahan dari server (pesan berbahasa Indonesia dari JSON server) atau jaringan. */
export class ApiError extends Error {
  /**
   * @param {string} message pesan untuk pengguna
   * @param {{code?: string|null, status?: number, network?: boolean}} [info]
   */
  constructor(message, { code = null, status = 0, network = false } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.network = network;
  }

  /** code 28000 = kunci owner tidak valid (atau sudah diganti) -> dashboard harus keluar. */
  get isAuth() {
    return this.code === '28000';
  }
}

/**
 * Panggil satu fungsi RPC. [params] = objek berisi SEMUA parameter p_* fungsi itu (boleh null).
 * @returns {Promise<any>} isi JSON jawaban
 * @throws {ApiError}
 */
export async function rpc(fn, params) {
  if (!/^[a-z_]+$/.test(fn)) throw new ApiError('Nama fungsi tidak valid.');
  const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), TIMEOUT_MS) : null;
  let res;
  try {
    res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, {
      method: 'POST',
      headers: { apikey: PUBLISHABLE_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
      cache: 'no-store',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      signal: ctrl ? ctrl.signal : undefined,
    });
  } catch (e) {
    const aborted = e && e.name === 'AbortError';
    throw new ApiError(
      aborted
        ? 'Server tidak menjawab (waktu habis). Periksa internet lalu coba lagi.'
        : 'Tidak bisa terhubung ke server. Periksa koneksi internet lalu coba lagi.',
      { network: true },
    );
  } finally {
    if (timer) clearTimeout(timer);
  }

  let text = '';
  try {
    text = await res.text();
  } catch {
    throw new ApiError('Jawaban server terputus. Coba lagi.', { network: true, status: res.status });
  }
  let body = null;
  if (text !== '') {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }
  if (!res.ok) {
    const code = body && typeof body.code === 'string' ? body.code : null;
    const message =
      body && typeof body.message === 'string' && body.message.trim() !== ''
        ? body.message
        : `Server menolak permintaan (HTTP ${res.status}).`;
    throw new ApiError(message, { code, status: res.status });
  }
  if (text !== '' && body === null && text.trim() !== 'null') {
    throw new ApiError('Jawaban server tidak terbaca.', { status: res.status });
  }
  return body;
}

// ---------------------------------------------------------------- RPC owner

/** owner_sites -> [{id, name}]. Juga dipakai untuk memeriksa kunci owner saat masuk. */
export function ownerSites(key) {
  return rpc('owner_sites', { p_key: key });
}

/** owner_overview -> {site, settings|null, employees, attendance, devices, serverTime}. [from]/[to] = "yyyy-mm-dd". */
export function ownerOverview(key, site, from, to) {
  return rpc('owner_overview', { p_key: key, p_site: site, p_from: from, p_to: to });
}

/** owner_save_settings -> {ok, version}. [baseVersion] = versi yang dimuat (0 bila settings masih null). */
export function ownerSaveSettings(key, site, data, baseVersion) {
  return rpc('owner_save_settings', { p_key: key, p_site: site, p_data: data, p_base_version: baseVersion });
}

/** owner_save_employee -> karyawan tersimpan. Tanpa id = karyawan baru. */
export function ownerSaveEmployee(key, site, employee) {
  return rpc('owner_save_employee', { p_key: key, p_site: site, p_employee: employee });
}

/** owner_create_pairing_code -> {code: "K7P2-9XQM", expiresAt}. */
export function ownerCreatePairingCode(key, site, label) {
  return rpc('owner_create_pairing_code', { p_key: key, p_site: site, p_label: label });
}

/** owner_revoke_device -> true bila diputus. */
export function ownerRevokeDevice(key, deviceId) {
  return rpc('owner_revoke_device', { p_key: key, p_device_id: deviceId });
}
