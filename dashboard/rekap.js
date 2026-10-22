/**
 * Logika murni dashboard owner: tanpa DOM, tanpa jaringan (diuji dengan node --test, lihat tests/rekap.test.mjs).
 *
 * Bagian rekap adalah port 1:1 dari kode Android supaya angka dan file ekspor dashboard SAMA PERSIS dengan aplikasi:
 *   - app/src/main/java/com/indohpl/presensi/domain/BonusCalculator.kt  (workDays, deductionPerDay, recap)
 *   - app/src/main/java/com/indohpl/presensi/domain/LateRule.kt         (lateMinutes)
 *   - app/src/main/java/com/indohpl/presensi/domain/GeoRule.kt          (formatDistance, formatCoord, parseLatLon)
 *   - app/src/main/java/com/indohpl/presensi/domain/RecapFormatter.kt   (monthLabel, rupiah, CSV, Markdown)
 *   - app/src/main/java/com/indohpl/presensi/data/SettingsStore.kt      (AppSettings.rulesText / shortRulesText)
 *   - app/src/main/java/com/indohpl/presensi/data/Entities.kt           (Employee.label)
 * Validasi pengaturan = private.validate_settings di supabase/migrations/20261020150000_presensi_core.sql.
 *
 * Konvensi: tanggal = string "yyyy-mm-dd" (LocalDate.toString), bulan = string "yyyy-mm" (YearMonth.toString),
 * hari dalam minggu = 1 (Senin) … 7 (Minggu) seperti java.time.DayOfWeek.value.
 */

export const STATUS = Object.freeze({ TEPAT: 'TEPAT', TELAT: 'TELAT', IZIN: 'IZIN', SAKIT: 'SAKIT' });

const MONTHS = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];
const MONTHS_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
const DAYS = ['Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu', 'Minggu'];
/** Label pendek hari kerja, indeks 0 = Senin (1) … 6 = Minggu (7). Sama dengan layar Pengaturan Android. */
export const DAY_SHORT = Object.freeze(['Sen', 'Sel', 'Rab', 'Kam', 'Jum', 'Sab', 'Min']);

const isHadir = (status) => status === STATUS.TEPAT || status === STATUS.TELAT;
const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k) && o[k] !== undefined;

// ---------------------------------------------------------------- tanggal & jam

export function pad2(n) {
  return String(n).padStart(2, '0');
}

/** yyyy-mm-dd dari jam LOKAL browser (bukan toISOString, yang memakai UTC dan bisa mundur sehari). */
export function localDateString(d = new Date()) {
  return `${String(d.getFullYear()).padStart(4, '0')}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** HH:mm:ss dari jam lokal browser. */
export function localTimeString(d = new Date()) {
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

/** Tanggal kalender yang benar-benar ada (2026-02-30 ditolak), seperti LocalDate.parse. */
export function isValidDate(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s ?? ''));
  if (!m) return false;
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  return mo >= 1 && mo <= 12 && d >= 1 && d <= daysIn(y, mo);
}

function isLeap(y) {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

function daysIn(y, m) {
  return [31, isLeap(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1];
}

function dateParts(dateStr) {
  const [y, m, d] = dateStr.split('-').map(Number);
  return { y, m, d };
}

/** 1 = Senin … 7 = Minggu (java.time.DayOfWeek.value). Dihitung dari kalender, tidak tergantung zona waktu. */
export function isoDayOfWeek(dateStr) {
  const { y, m, d } = dateParts(dateStr);
  const w = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return w === 0 ? 7 : w;
}

/** "2026-10-22" -> "2026-10". */
export function ymOf(dateStr) {
  return String(dateStr).slice(0, 7);
}

function ymParts(ym) {
  const [y, m] = ym.split('-').map(Number);
  return { y, m };
}

/** YearMonth.plusMonths. */
export function addMonths(ym, n) {
  const { y, m } = ymParts(ym);
  const t = y * 12 + (m - 1) + n;
  return `${String(Math.floor(t / 12)).padStart(4, '0')}-${pad2((((t % 12) + 12) % 12) + 1)}`;
}

/** YearMonth.lengthOfMonth. */
export function lengthOfMonth(ym) {
  const { y, m } = ymParts(ym);
  return daysIn(y, m);
}

/** Rentang tanggal satu bulan untuk owner_overview (p_from, p_to). */
export function monthRange(ym) {
  return { from: `${ym}-01`, to: `${ym}-${pad2(lengthOfMonth(ym))}` };
}

/** RecapFormatter.monthLabel: "September 2026". */
export function monthLabel(ym) {
  const { y, m } = ymParts(ym);
  return `${MONTHS[m - 1]} ${y}`;
}

/** "Kamis, 22 Oktober 2026" (format LONG_DATE Android, locale id-ID). */
export function longDateLabel(dateStr) {
  const { y, m, d } = dateParts(dateStr);
  return `${DAYS[isoDayOfWeek(dateStr) - 1]}, ${d} ${MONTHS[m - 1]} ${y}`;
}

/** "Kam 22 Okt" (format SHORT_DATE Android, locale id-ID). */
export function shortDateLabel(dateStr) {
  const { m, d } = dateParts(dateStr);
  return `${DAY_SHORT[isoDayOfWeek(dateStr) - 1]} ${d} ${MONTHS_SHORT[m - 1]}`;
}

/** "22 Okt 2026" dari "yyyy-mm-dd". */
export function mediumDateLabel(dateStr) {
  const { y, m, d } = dateParts(dateStr);
  return `${d} ${MONTHS_SHORT[m - 1]} ${y}`;
}

/** RecapFormatter.DATE_TIME_FMT (dd/MM/yyyy HH:mm:ss) dari jam lokal, untuk baris "Dibuat:" di Markdown. */
export function generatedAtLabel(d = new Date()) {
  return `${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}/${d.getFullYear()} ${localTimeString(d)}`;
}

/** "22 Okt 2026 10:21" (jam lokal) dari epoch milidetik. */
export function dateTimeLabel(ms) {
  const d = new Date(ms);
  return `${mediumDateLabel(localDateString(d))} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/**
 * Waktu dari server (timestamptz JSON Postgres, mis. "2026-10-22T07:28:10.123456+00:00") -> epoch milidetik.
 * Pecahan detik dinormalkan ke 3 angka supaya pasti terbaca semua browser. null bila kosong/tidak terbaca.
 */
export function parseTimestamp(v) {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  const m = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2})?)(\.\d+)?(Z|[+-]\d{2}(?::?\d{2})?)?$/i.exec(String(v).trim());
  if (!m) {
    const t = Date.parse(String(v));
    return Number.isNaN(t) ? null : t;
  }
  const time = m[2].length === 5 ? `${m[2]}:00` : m[2];
  const frac = m[3] ? `.${(m[3].slice(1) + '00').slice(0, 3)}` : '';
  let tz = m[4] ? m[4].toUpperCase() : 'Z';
  if (tz !== 'Z') {
    const digits = tz.slice(1).replace(':', '');
    tz = `${tz[0]}${digits.slice(0, 2)}:${(digits.slice(2) + '00').slice(0, 2)}`;
  }
  const t = Date.parse(`${m[1]}T${time}${frac}${tz}`);
  return Number.isNaN(t) ? null : t;
}

// ---------------------------------------------------------------- LateRule.kt

/** LateRule.parseWorkStart: "HH:mm" (dua angka) -> menit sejak 00:00, atau null. */
export function parseWorkStart(text) {
  const m = /^(\d{2}):(\d{2})$/.exec(String(text ?? '').trim());
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return h * 60 + min;
}

/** "HH:mm" atau "HH:mm:ss" -> detik sejak 00:00, atau null. */
export function parseClock(text) {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(String(text ?? '').trim());
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  const s = m[3] ? Number(m[3]) : 0;
  if (h > 23 || min > 59 || s > 59) return null;
  return h * 3600 + min * 60 + s;
}

/**
 * LateRule.lateMinutes: menit keterlambatan (dibulatkan ke bawah, dihitung dari jam masuk), 0 bila tidak telat.
 * Contoh: jam masuk 08:00 toleransi 5 -> batas 08:05:00. Datang 08:05:00 -> 0; 08:05:01 -> 5; 08:06:00 -> 6.
 * [arrival] = "HH:mm:ss" (LocalTime).
 */
export function lateMinutes(arrival, workStart, toleranceMinutes) {
  const startSec = (parseWorkStart(workStart) ?? 8 * 60) * 60; // ?: LocalTime.of(8, 0)
  const arrivalSec = parseClock(arrival);
  if (arrivalSec == null) return 0;
  const tol = Math.max(0, Math.trunc(Number(toleranceMinutes) || 0));
  const limitSec = (startSec + (tol % 1440) * 60) % 86400; // LocalTime.plusMinutes melingkar di tengah malam
  if (!(arrivalSec > limitSec)) return 0;
  // ChronoUnit.MINUTES.between memotong ke arah nol; coerceAtLeast(1).
  return Math.max(1, Math.trunc((arrivalSec - startSec) / 60));
}

// ---------------------------------------------------------------- pengaturan (AppSettings + kontrak cloud)

/** Nilai bawaan AppSettings (dipakai bila site.defaultSettings maupun pengaturan cloud tidak punya kunci itu). */
export const APP_DEFAULTS = Object.freeze({
  workStartFemale: '07:45',
  toleranceFemale: 10,
  workStartMale: '08:00',
  toleranceMale: 5,
  monthlyBonus: 250000,
  lateDeduction: 0,
  deductAbsent: true,
  workDays: Object.freeze([1, 2, 3, 4, 5, 6, 7]),
  storeLat: null,
  storeLon: null,
  radiusMeters: 100,
  requireLocation: true,
  requireBiometric: true,
  photoRetentionDays: 60,
  holidays: Object.freeze([]),
});

const TIME_KEYS = ['workStartFemale', 'workStartMale'];
const NUMBER_KEYS = ['toleranceFemale', 'toleranceMale', 'monthlyBonus', 'lateDeduction', 'radiusMeters', 'photoRetentionDays'];
const BOOL_KEYS = ['deductAbsent', 'requireLocation', 'requireBiometric'];

/**
 * Gabungkan sumber pengaturan (urutan: yang belakangan menang), mis. normalizeSettings(site.defaultSettings, settings.data).
 * Kunci yang tipenya salah diabaikan. Angka dibulatkan seperti SettingsJson di aplikasi Android (roundToLong).
 */
export function normalizeSettings(...sources) {
  const out = { ...APP_DEFAULTS, workDays: [...APP_DEFAULTS.workDays], holidays: [] };
  for (const src of sources) {
    if (!isPlainObject(src)) continue;
    for (const k of TIME_KEYS) if (typeof src[k] === 'string') out[k] = src[k];
    for (const k of NUMBER_KEYS) if (isNum(src[k])) out[k] = Math.round(src[k]); // roundToLong, sama dengan SettingsJson Android
    for (const k of BOOL_KEYS) if (typeof src[k] === 'boolean') out[k] = src[k];
    for (const k of ['storeLat', 'storeLon']) {
      if (has(src, k) && (src[k] === null || isNum(src[k]))) out[k] = src[k];
    }
    if (Array.isArray(src.workDays)) {
      const days = [...new Set(src.workDays.filter((d) => Number.isInteger(d) && d >= 1 && d <= 7))].sort((a, b) => a - b);
      if (days.length > 0) out.workDays = days;
    }
    if (Array.isArray(src.holidays)) {
      out.holidays = src.holidays
        .filter((h) => isPlainObject(h) && typeof h.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(h.date))
        .map((h) => ({ date: h.date, name: typeof h.name === 'string' ? h.name : String(h.name ?? '') }));
    }
  }
  return out;
}

/** Titik lokasi kerja sudah diatur (AppSettings.hasStoreLocation). */
export function hasStoreLocation(s) {
  return s.storeLat != null && s.storeLon != null;
}

export function workStartFor(s, gender) {
  return gender === 'P' ? s.workStartFemale : s.workStartMale;
}

export function toleranceFor(s, gender) {
  return gender === 'P' ? s.toleranceFemale : s.toleranceMale;
}

/** AppSettings.sameRuleForAll: aturan perempuan dan laki-laki sama persis -> ditampilkan satu kali. */
export function sameRuleForAll(s) {
  return s.workStartFemale === s.workStartMale && s.toleranceFemale === s.toleranceMale;
}

/** AppSettings.rulesText(): dipakai di ekspor Markdown. */
export function rulesText(s) {
  return sameRuleForAll(s)
    ? `${s.workStartFemale} (toleransi ${s.toleranceFemale} mnt)`
    : `Perempuan ${s.workStartFemale} (toleransi ${s.toleranceFemale} mnt) · Laki-laki ${s.workStartMale} (toleransi ${s.toleranceMale} mnt)`;
}

/** AppSettings.shortRulesText(): "07:30 (+10 mnt)" atau "P 07:45 (+10 mnt) · L 08:00 (+5 mnt)". */
export function shortRulesText(s) {
  return sameRuleForAll(s)
    ? `${s.workStartFemale} (+${s.toleranceFemale} mnt)`
    : `P ${s.workStartFemale} (+${s.toleranceFemale} mnt) · L ${s.workStartMale} (+${s.toleranceMale} mnt)`;
}

/** AppSettings.bonusConfig(). */
export function bonusConfig(s) {
  return { monthlyBonus: s.monthlyBonus, lateDeductionOverride: s.lateDeduction, deductAbsent: s.deductAbsent };
}

/** Batas dianggap tepat untuk satu gender, mis. "07:40" (jam masuk + toleransi). */
export function limitLabel(s, gender) {
  const start = parseWorkStart(workStartFor(s, gender)) ?? 8 * 60;
  const t = (start + Math.max(0, toleranceFor(s, gender))) % 1440;
  return `${pad2(Math.floor(t / 60))}:${pad2(t % 60)}`;
}

export function holidayDates(s) {
  return new Set(s.holidays.map((h) => h.date));
}

/** Teks jsonb::text Postgres (", " dan ": " sebagai pemisah) untuk meniru batas ukuran 30.000 karakter server. */
export function jsonbText(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return `[${v.map(jsonbText).join(', ')}]`;
  if (typeof v === 'object') {
    return `{${Object.keys(v)
      .filter((k) => v[k] !== undefined)
      .map((k) => `${JSON.stringify(k)}: ${jsonbText(v[k])}`)
      .join(', ')}}`;
  }
  return JSON.stringify(v);
}

/** h->>'x' Postgres: teks nilai JSON (string apa adanya, selain itu teks JSON-nya), null bila null/tidak ada. */
function jsonText(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'string') return v;
  return jsonbText(v);
}

/**
 * Port private.validate_settings (server). Aturan dan urutan pemeriksaan sama persis; pesannya dibuat lebih jelas.
 * @returns {string|null} pesan kesalahan pertama, atau null bila valid.
 */
export function validateSettings(d) {
  if (!isPlainObject(d)) return 'Pengaturan harus berupa objek JSON.';
  if ([...jsonbText(d)].length > 30000) return 'Pengaturan terlalu besar (maks 30.000 karakter). Kurangi daftar hari libur.';
  for (const [k, who] of [['workStartFemale', 'perempuan'], ['workStartMale', 'laki-laki']]) {
    if (has(d, k) && (typeof d[k] !== 'string' || !/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(d[k]))) {
      return `Jam masuk ${who} harus HH:mm, contoh 07:30.`;
    }
  }
  for (const [k, who] of [['toleranceFemale', 'perempuan'], ['toleranceMale', 'laki-laki']]) {
    if (has(d, k) && (!isNum(d[k]) || d[k] < 0 || d[k] > 240)) return `Toleransi ${who} harus 0-240 menit.`;
  }
  if (has(d, 'monthlyBonus') && (!isNum(d.monthlyBonus) || d.monthlyBonus < 0 || d.monthlyBonus > 100000000)) {
    return 'Uang rajin harus 0 sampai 100.000.000.';
  }
  if (has(d, 'lateDeduction') && (!isNum(d.lateDeduction) || d.lateDeduction < 0 || d.lateDeduction > 100000000)) {
    return 'Potongan per hari telat harus 0 sampai 100.000.000 (0 = otomatis).';
  }
  if (has(d, 'workDays')) {
    const w = d.workDays;
    if (Array.isArray(w) && w.length === 0) return 'Pilih minimal satu hari kerja.';
    if (!Array.isArray(w) || w.length > 7 || w.some((e) => !isNum(e) || ![1, 2, 3, 4, 5, 6, 7].includes(e))) {
      return 'Hari kerja harus daftar angka 1 (Senin) sampai 7 (Minggu).';
    }
  }
  if (has(d, 'radiusMeters') && (!isNum(d.radiusMeters) || d.radiusMeters < 10 || d.radiusMeters > 100000)) {
    return 'Radius minimal 10 meter (maks 100.000).';
  }
  if (has(d, 'photoRetentionDays')) {
    const p = d.photoRetentionDays;
    if (!isNum(p) || !(p === 0 || (p >= 7 && p <= 3650))) return 'Simpan foto: 0 (selamanya) atau 7-3650 hari.';
  }
  if (has(d, 'storeLat') && d.storeLat !== null && (!isNum(d.storeLat) || d.storeLat < -90 || d.storeLat > 90)) {
    return 'Lintang tidak valid (harus -90 sampai 90).';
  }
  if (has(d, 'storeLon') && d.storeLon !== null && (!isNum(d.storeLon) || d.storeLon < -180 || d.storeLon > 180)) {
    return 'Bujur tidak valid (harus -180 sampai 180).';
  }
  for (const k of BOOL_KEYS) {
    if (has(d, k) && typeof d[k] !== 'boolean') return `${k} harus true/false.`;
  }
  if (has(d, 'holidays')) {
    if (!Array.isArray(d.holidays) || d.holidays.length > 400) return 'Daftar hari libur tidak valid (maks 400 tanggal).';
    for (const h of d.holidays) {
      if (!isPlainObject(h) || !/^\d{4}-\d{2}-\d{2}$/.test(jsonText(h.date) ?? '') || [...(jsonText(h.name) ?? '')].length > 60) {
        return 'Hari libur harus bertanggal yyyy-mm-dd dengan nama maks 60 huruf.';
      }
    }
  }
  return null;
}

/** Angka bulat dari teks ("+5", "10"). null bila bukan angka bulat. */
export function parseIntText(text) {
  const t = String(text ?? '').trim();
  if (!/^[+-]?\d{1,15}$/.test(t)) return null;
  return Number(t);
}

/** Rupiah dari teks: "250000", "250.000", "Rp 250.000". null bila tidak terbaca. */
export function parseRupiahText(text) {
  const t = String(text ?? '').trim().replace(/^rp\.?\s*/i, '');
  if (/^\d{1,3}(\.\d{3})+$/.test(t)) return Number(t.replaceAll('.', ''));
  return parseIntText(t);
}

/**
 * Isi form Pengaturan (teks mentah) -> data Settings untuk owner_save_settings.
 * Urutan & aturan parse mengikuti SettingsScreen.save() Android, lalu validateSettings (sama dengan server).
 * Kunci lain di [base] (data mentah yang dimuat) dipertahankan supaya tidak hilang.
 * @returns {{data: object}|{error: string, field: string}}
 */
export function settingsFromForm(v, base = {}) {
  const fail = (field, error) => ({ error, field });
  const wsF = String(v.workStartFemale ?? '').trim();
  const wsM = String(v.workStartMale ?? '').trim();
  if (parseWorkStart(wsF) == null) return fail('workStartFemale', 'Jam masuk harus format HH:mm, contoh 07:30.');
  if (parseWorkStart(wsM) == null) return fail('workStartMale', 'Jam masuk harus format HH:mm, contoh 07:30.');
  const tolF = parseIntText(v.toleranceFemale);
  const tolM = parseIntText(v.toleranceMale);
  if (tolF == null || tolF < 0) return fail('toleranceFemale', 'Toleransi harus angka ≥ 0.');
  if (tolM == null || tolM < 0) return fail('toleranceMale', 'Toleransi harus angka ≥ 0.');
  const bonus = parseRupiahText(v.monthlyBonus);
  if (bonus == null || bonus < 0) return fail('monthlyBonus', 'Uang rajin harus angka ≥ 0.');
  const ded = parseRupiahText(v.lateDeduction);
  if (ded == null || ded < 0) return fail('lateDeduction', 'Potongan harus angka ≥ 0 (0 = otomatis).');
  const days = [...new Set((v.workDays ?? []).map(Number))].filter((d) => d >= 1 && d <= 7).sort((a, b) => a - b);
  if (days.length === 0) return fail('workDays', 'Pilih minimal satu hari kerja.');
  const locText = String(v.location ?? '').trim();
  const latLon = locText === '' ? null : parseLocationText(locText);
  if (locText !== '' && latLon == null) {
    return fail('location', 'Koordinat harus "lat, lon", contoh -6.200000, 106.816666');
  }
  const radius = parseIntText(v.radiusMeters);
  if (radius == null || radius < 10) return fail('radiusMeters', 'Radius minimal 10 meter.');
  const keep = parseIntText(v.photoRetentionDays);
  if (keep == null || !(keep === 0 || (keep >= 7 && keep <= 3650))) {
    return fail('photoRetentionDays', 'Simpan foto: 0 (selamanya) atau 7-3650 hari.');
  }
  const holidays = (v.holidays ?? [])
    .map((h) => ({ date: String(h.date), name: String(h.name ?? '') }))
    .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
  const data = {
    ...(isPlainObject(base) ? base : {}),
    workStartFemale: wsF,
    toleranceFemale: tolF,
    workStartMale: wsM,
    toleranceMale: tolM,
    monthlyBonus: bonus,
    lateDeduction: ded,
    deductAbsent: Boolean(v.deductAbsent),
    workDays: days,
    storeLat: latLon ? latLon[0] : null,
    storeLon: latLon ? latLon[1] : null,
    radiusMeters: radius,
    requireLocation: Boolean(v.requireLocation),
    requireBiometric: Boolean(v.requireBiometric),
    photoRetentionDays: keep,
    holidays,
  };
  const err = validateSettings(data);
  if (err) return fail(fieldOfValidationError(err), err);
  return { data };
}

function fieldOfValidationError(msg) {
  if (msg.startsWith('Jam masuk perempuan')) return 'workStartFemale';
  if (msg.startsWith('Jam masuk laki-laki')) return 'workStartMale';
  if (msg.startsWith('Toleransi perempuan')) return 'toleranceFemale';
  if (msg.startsWith('Toleransi laki-laki')) return 'toleranceMale';
  if (msg.startsWith('Uang rajin')) return 'monthlyBonus';
  if (msg.startsWith('Potongan')) return 'lateDeduction';
  if (msg.includes('hari kerja') || msg.startsWith('Hari kerja')) return 'workDays';
  if (msg.startsWith('Radius')) return 'radiusMeters';
  if (msg.startsWith('Simpan foto')) return 'photoRetentionDays';
  if (msg.startsWith('Lintang') || msg.startsWith('Bujur')) return 'location';
  if (msg.includes('libur') || msg.includes('terlalu besar')) return 'holidays';
  return '';
}

/** Nama ramah untuk tiap kunci pengaturan (dipakai daftar perbedaan saat bentrok versi). */
export const SETTINGS_LABELS = Object.freeze({
  workStartFemale: 'Jam masuk perempuan',
  toleranceFemale: 'Toleransi perempuan',
  workStartMale: 'Jam masuk laki-laki',
  toleranceMale: 'Toleransi laki-laki',
  workDays: 'Hari kerja',
  monthlyBonus: 'Uang rajin per bulan',
  lateDeduction: 'Potongan per hari telat',
  deductAbsent: 'Potong hari alpa',
  requireBiometric: 'Wajib biometrik',
  location: 'Titik lokasi',
  radiusMeters: 'Radius',
  requireLocation: 'Wajib di lokasi',
  photoRetentionDays: 'Simpan foto di HP',
  holidays: 'Hari libur',
});

/** Nilai satu kunci pengaturan sebagai teks pendek. [s] = hasil normalizeSettings. */
export function settingValueText(key, s) {
  switch (key) {
    case 'toleranceFemale':
    case 'toleranceMale':
      return `${s[key]} mnt`;
    case 'monthlyBonus':
      return rupiah(s.monthlyBonus);
    case 'lateDeduction':
      return s.lateDeduction > 0 ? rupiah(s.lateDeduction) : 'otomatis';
    case 'workDays':
      return s.workDays.map((d) => DAY_SHORT[d - 1]).join(', ');
    case 'deductAbsent':
    case 'requireBiometric':
    case 'requireLocation':
      return s[key] ? 'ya' : 'tidak';
    case 'location':
      return hasStoreLocation(s) ? formatCoord(s.storeLat, s.storeLon) : 'belum diatur';
    case 'radiusMeters':
      return `${s.radiusMeters} m`;
    case 'photoRetentionDays':
      return s.photoRetentionDays === 0 ? 'selamanya' : `${s.photoRetentionDays} hari`;
    case 'holidays':
      return s.holidays.length === 0 ? 'tidak ada' : s.holidays.map((h) => `${h.date} ${h.name}`.trim()).join('; ');
    default:
      return String(s[key]);
  }
}

/** Kunci pengaturan yang berbeda antara [a] dan [b] (keduanya hasil normalizeSettings). */
export function settingsDiff(a, b) {
  const keyValue = (s, k) => {
    if (k === 'location') return JSON.stringify([s.storeLat, s.storeLon]);
    if (k === 'holidays') return JSON.stringify([...s.holidays].sort((x, y) => (x.date < y.date ? -1 : x.date > y.date ? 1 : 0)));
    return JSON.stringify(s[k]);
  };
  return Object.keys(SETTINGS_LABELS).filter((k) => keyValue(a, k) !== keyValue(b, k));
}

// ---------------------------------------------------------------- tempat kerja

const SITE_TEXT = {
  egkost: {
    place: 'kost',
    placeFull: 'EG Kost',
    device: 'HP presensi',
    appTitle: 'Presensi EG Kost',
    radiusHint: '100 m cukup untuk satu bangunan kost dan halamannya.',
  },
  indohpl: {
    place: 'toko',
    placeFull: 'toko Indo HPL',
    device: 'HP kasir',
    appTitle: 'Presensi Indo HPL',
    radiusHint: '100 m cukup untuk toko di dalam ruko/mall.',
  },
};

/** Kata-kata per tempat kerja (sama dengan Site.kt tiap varian). */
export function siteText(siteId, siteName = '') {
  return (
    SITE_TEXT[siteId] ?? {
      place: 'toko',
      placeFull: siteName || 'tempat kerja',
      device: 'HP presensi',
      appTitle: `Presensi ${siteName}`.trim(),
      radiusHint: '',
    }
  );
}

/** Kata tempat untuk kolom ekspor: egkost -> "kost" (jarak_kost_m), indohpl -> "toko" (jarak_toko_m). */
export function placeWord(siteId) {
  return siteText(siteId).place;
}

// ---------------------------------------------------------------- Entities.kt

/** Employee.label: "Inul · Laundry" bila ada bagian kerja, selain itu hanya nama. */
export function employeeLabel(emp) {
  const role = emp.role ?? '';
  return role.trim() === '' ? emp.name : `${emp.name} · ${role}`;
}

// ---------------------------------------------------------------- BonusCalculator.kt

/** BonusCalculator.workDays: daftar tanggal hari kerja bulan [ym] (hari kerja dalam minggu, kecuali libur). */
export function workDays(ym, workDayOfWeeks, holidays) {
  const dows = new Set([...workDayOfWeeks].map(Number));
  const hol = new Set(holidays);
  const out = [];
  const n = lengthOfMonth(ym);
  for (let d = 1; d <= n; d++) {
    const date = `${ym}-${pad2(d)}`;
    if (dows.has(isoDayOfWeek(date)) && !hol.has(date)) out.push(date);
  }
  return out;
}

/** BonusCalculator.deductionPerDay: potongan tetap bila diisi, selain itu Math.round(bonus / hari kerja). */
export function deductionPerDay(config, workDaysInMonth) {
  if (config.lateDeductionOverride > 0) return config.lateDeductionOverride;
  if (workDaysInMonth <= 0) return 0;
  return Math.round(config.monthlyBonus / workDaysInMonth);
}

/**
 * BonusCalculator.recap: rekap semua karyawan untuk bulan [ym].
 * [today] ("yyyy-mm-dd") menentukan hari kerja yang sudah lewat (untuk alpa di bulan berjalan).
 */
export function recap({ ym, today, employees, records, workDayOfWeeks, holidays, config }) {
  const days = workDays(ym, workDayOfWeeks, holidays);
  const elapsed = days.filter((d) => !(d > today));
  const perDay = deductionPerDay(config, days.length);
  const byEmployee = new Map();
  for (const r of records) {
    if (!byEmployee.has(r.employeeId)) byEmployee.set(r.employeeId, []);
    byEmployee.get(r.employeeId).push(r);
  }
  return employees.map((employee) => {
    const recs = byEmployee.get(employee.id) ?? [];
    const byDate = new Map();
    for (const r of recs) byDate.set(r.date, r); // associateBy: yang terakhir menang
    const count = (pred) => recs.reduce((n, r) => (pred(r) ? n + 1 : n), 0);
    const tepat = count((r) => r.status === STATUS.TEPAT);
    const telat = count((r) => r.status === STATUS.TELAT);
    const izin = count((r) => r.status === STATUS.IZIN);
    const sakit = count((r) => r.status === STATUS.SAKIT);
    const alpa = elapsed.filter((d) => !byDate.has(d)).length;
    const outsideLocation = count((r) => isHadir(r.status) && !r.inLocation);
    const deductedDays = telat + (config.deductAbsent ? alpa : 0);
    const totalDeduction = Math.min(perDay * deductedDays, config.monthlyBonus);
    const isFullMonth = days.length > 0 && days.every((d) => byDate.get(d)?.status === STATUS.TEPAT);
    return {
      employee,
      workDaysInMonth: days.length,
      workDaysElapsed: elapsed.length,
      hadir: tepat + telat,
      tepat,
      telat,
      izin,
      sakit,
      alpa,
      outsideLocation,
      totalLateMinutes: recs.reduce((s, r) => s + (Number(r.lateMinutes) || 0), 0),
      deductionPerDay: perDay,
      deductedDays,
      totalDeduction,
      bonus: Math.max(config.monthlyBonus - totalDeduction, 0),
      isFullMonth,
    };
  });
}

/**
 * Detail harian satu karyawan untuk tampilan Rekap: semua catatan bulan itu + hari kerja lewat tanpa catatan (alpa),
 * terbaru di atas.
 */
export function employeeDays({ ym, today, employeeId, records, workDayOfWeeks, holidays }) {
  const recs = records.filter((r) => r.employeeId === employeeId);
  const byDate = new Map(recs.map((r) => [r.date, r]));
  const rows = [...byDate.values()].map((record) => ({ date: record.date, record, alpa: false }));
  for (const d of workDays(ym, workDayOfWeeks, holidays)) {
    if (!(d > today) && !byDate.has(d)) rows.push({ date: d, record: null, alpa: true });
  }
  return rows.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
}

// ---------------------------------------------------------------- angka gaya Java

/**
 * String.format(Locale.US, "%.Nf", x): pembulatan HALF_UP pada representasi desimal terpendek (bukan toFixed,
 * yang membulatkan nilai biner: 1.15.toFixed(1) = "1.1", Java = "1.2").
 */
export function formatFixed(x, digits) {
  if (Number.isNaN(x)) return 'NaN';
  if (!Number.isFinite(x)) return x > 0 ? 'Infinity' : '-Infinity';
  const neg = x < 0 || Object.is(x, -0);
  const [mant, expText] = Math.abs(x).toString().split('e');
  const exp = expText ? parseInt(expText, 10) : 0;
  const [ip, fp = ''] = mant.split('.');
  const all = ip + fp;
  const point = ip.length + exp;
  let intStr;
  let fracStr;
  if (point <= 0) {
    intStr = '0';
    fracStr = '0'.repeat(-point) + all;
  } else if (point >= all.length) {
    intStr = all + '0'.repeat(point - all.length);
    fracStr = '';
  } else {
    intStr = all.slice(0, point);
    fracStr = all.slice(point);
  }
  const roundUp = fracStr.length > digits && fracStr.charCodeAt(digits) >= 53; // '5'..'9'
  let num = intStr + (fracStr + '0'.repeat(digits)).slice(0, digits);
  if (roundUp) num = (BigInt(num) + 1n).toString().padStart(num.length, '0');
  const cut = num.length - digits;
  let out = digits > 0 ? `${num.slice(0, cut)}.${num.slice(cut)}` : num;
  out = out.replace(/^0+(?=\d)/, '');
  return (neg ? '-' : '') + out;
}

/** Double.toString Java/Kotlin: "106.8", "-6.0" (selalu ada ".0"), "5.0E-4" di bawah 0,001 / dari 10^7. */
export function javaDoubleToString(x) {
  if (Number.isNaN(x)) return 'NaN';
  if (x === Infinity) return 'Infinity';
  if (x === -Infinity) return '-Infinity';
  if (x === 0) return Object.is(x, -0) ? '-0.0' : '0.0';
  const sign = x < 0 ? '-' : '';
  const abs = Math.abs(x);
  if (abs >= 1e-3 && abs < 1e7) {
    const s = abs.toString();
    return sign + (s.includes('.') ? s : `${s}.0`);
  }
  const [m, e] = abs.toExponential().split('e');
  return `${sign}${m.includes('.') ? m : `${m}.0`}E${parseInt(e, 10)}`;
}

// ---------------------------------------------------------------- GeoRule.kt

/** GeoRule.formatDistance: "35 m" atau "1,2 km". */
export function formatDistance(meters) {
  return meters < 1000 ? `${meters} m` : `${formatFixed(meters / 1000, 1)} km`.replaceAll('.', ',');
}

/** GeoRule.formatCoord: "-6.175392, 106.827153". */
export function formatCoord(lat, lon) {
  return `${formatFixed(lat, 6)}, ${formatFixed(lon, 6)}`;
}

/** String.toDoubleOrNull (angka desimal biasa; NaN/Infinity akhirnya ditolak cek rentang di GeoRule juga). */
function toDoubleOrNull(text) {
  const t = text.trim();
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[fFdD]?$/.test(t)) return null;
  return Number(t.replace(/[fFdD]$/, ''));
}

/** GeoRule.parseLatLon: terima "-6.200000, 106.816666" (format salin Google Maps) atau "-6.2 106.816666". */
export function parseLatLon(text) {
  const parts = String(text ?? '').trim().split(/[, ]/).filter((p) => p.trim() !== '');
  if (parts.length !== 2) return null;
  const lat = toDoubleOrNull(parts[0]);
  const lon = toDoubleOrNull(parts[1]);
  if (lat == null || lon == null) return null;
  if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) return null;
  return [lat, lon];
}

/**
 * parseLatLon, ditambah: tautan Google Maps yang ditempel (".../@-6.2,106.8,17z", "?q=-6.2,106.8").
 * @returns {[number, number]|null}
 */
export function parseLocationText(text) {
  const direct = parseLatLon(text);
  if (direct) return direct;
  const t = String(text ?? '');
  const patterns = [
    /@(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)/,
    /[?&](?:q|query|ll|destination)=(-?\d{1,2}(?:\.\d+)?)(?:,|%2C)\s*(-?\d{1,3}(?:\.\d+)?)/i,
    /(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)/,
  ];
  for (const re of patterns) {
    const m = re.exec(t);
    if (!m) continue;
    const lat = Number(m[1]);
    const lon = Number(m[2]);
    if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) return [lat, lon];
  }
  return null;
}

/** Tautan Google Maps untuk satu titik. */
export function mapsUrl(lat, lon) {
  return `https://www.google.com/maps?q=${Number(lat)},${Number(lon)}`;
}

// ---------------------------------------------------------------- RecapFormatter.kt

/** RecapFormatter.rupiah: "Rp 250.000". */
export function rupiah(v) {
  const n = Math.trunc(Number(v) || 0);
  const grouped = String(Math.abs(n)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `Rp ${n < 0 ? '-' : ''}${grouped}`;
}

function csvCell(s) {
  return /[,"\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

function compareNullable(a, b) {
  if (a === b) return 0;
  if (a == null) return -1;
  if (b == null) return 1;
  return a < b ? -1 : a > b ? 1 : 0;
}

/** records.sortedWith(compareBy({ it.date }, { it.timestamp })) — stabil. */
function sortRecords(records) {
  return [...records].sort((a, b) => compareNullable(a.date, b.date) || compareNullable(a.timestamp, b.timestamp));
}

/** Nama file foto (cloud tidak menyimpan foto, jadi selalu kosong untuk data dashboard). */
function photoName(r) {
  if (typeof r.photoPath !== 'string') return '';
  return r.photoPath.slice(r.photoPath.lastIndexOf('/') + 1);
}

/** RecapFormatter.detailCsv: satu baris per presensi. [place] = "toko"/"kost" (kolom jarak_<place>_m). */
export function detailCsv(ym, employees, records, place) {
  const names = new Map(employees.map((e) => [e.id, e.name]));
  let out = `tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_${place}_m,di_lokasi,ket_lokasi\n`;
  for (const r of sortRecords(records)) {
    out += [
      String(r.date),
      names.has(r.employeeId) ? names.get(r.employeeId) : String(r.employeeId),
      String(r.status),
      r.timeIn ?? '',
      String(r.lateMinutes ?? 0),
      r.note ?? '',
      photoName(r),
      r.latitude != null ? javaDoubleToString(r.latitude) : '',
      r.longitude != null ? javaDoubleToString(r.longitude) : '',
      r.accuracy != null ? String(Math.trunc(r.accuracy)) : '',
      r.distanceMeters != null ? String(r.distanceMeters) : '',
      isHadir(r.status) ? (r.inLocation ? 'YA' : 'TIDAK') : '',
      r.locationNote ?? '',
    ]
      .map(csvCell)
      .join(',');
    out += '\n';
  }
  return out;
}

/** RecapFormatter.summaryCsv: ringkasan per karyawan. */
export function summaryCsv(ym, recaps) {
  let out =
    'bulan,nama,hari_kerja,hadir,tepat,telat,izin,sakit,alpa,di_luar_lokasi,total_menit_telat,potongan_per_hari,hari_dipotong,total_potongan,uang_rajin,full_bulan\n';
  for (const r of recaps) {
    out += [
      ym,
      r.employee.name,
      r.workDaysInMonth,
      r.hadir,
      r.tepat,
      r.telat,
      r.izin,
      r.sakit,
      r.alpa,
      r.outsideLocation,
      r.totalLateMinutes,
      r.deductionPerDay,
      r.deductedDays,
      r.totalDeduction,
      r.bonus,
      r.isFullMonth ? 'YA' : 'TIDAK',
    ]
      .map((v) => csvCell(String(v)))
      .join(',');
    out += '\n';
  }
  return out;
}

/** RecapFormatter.markdown: rekap siap tempel ke Claude. */
export function markdown(ym, recaps, records, employees, config, rules, generatedAt, place) {
  const names = new Map(employees.map((e) => [e.id, e.name]));
  let s = `# Rekap Presensi ${monthLabel(ym)}\n\n`;
  s += `Dibuat: ${generatedAt}\n`;
  s += `Jam masuk: ${rules}. `;
  s += `Uang rajin penuh: ${rupiah(config.monthlyBonus)}/bulan. `;
  const perDay = recaps.length > 0 ? recaps[0].deductionPerDay : 0;
  s += `Potongan per hari telat: ${rupiah(perDay)}`;
  s += config.deductAbsent ? ' (alpa juga dipotong).\n\n' : ' (alpa tidak dipotong).\n\n';

  s += '## Ringkasan\n\n';
  s += '| Nama | Hari kerja | Hadir | Tepat | Telat | Izin | Sakit | Alpa | Di luar lokasi | Potongan | Uang rajin |\n';
  s += '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n';
  for (const r of recaps) {
    s += `| ${employeeLabel(r.employee)} | ${r.workDaysInMonth} | ${r.hadir} | ${r.tepat} | ${r.telat} | ${r.izin} | ${r.sakit} | ${r.alpa} | ${r.outsideLocation} | ${rupiah(r.totalDeduction)} | **${rupiah(r.bonus)}** |\n`;
  }
  s += '\n## Detail harian\n\n';
  s += `| Tanggal | Nama | Status | Jam masuk | Telat (menit) | Lokasi | Jarak ${place} | Koordinat | Catatan |\n`;
  s += '|---|---|---|---|---:|---|---:|---|---|\n';
  for (const r of sortRecords(records)) {
    const lokasi = !isHadir(r.status) ? '' : r.inLocation ? 'di lokasi' : `DI LUAR (${r.locationNote ?? ''})`;
    const jarak = r.distanceMeters != null ? formatDistance(r.distanceMeters) : '';
    const koord = r.latitude != null && r.longitude != null ? formatCoord(r.latitude, r.longitude) : '';
    const name = names.has(r.employeeId) ? names.get(r.employeeId) : r.employeeId;
    s += `| ${r.date} | ${name} | ${r.status} | ${r.timeIn ?? ''} | ${r.lateMinutes ?? 0} | ${lokasi} | ${jarak} | ${koord} | ${r.note ?? ''} |\n`;
  }
  return s;
}

/** Nama tiga file ekspor (sama dengan ExportPanel Android). */
export function exportFileNames(ym) {
  return { detail: `presensi_${ym}_detail.csv`, summary: `presensi_${ym}_rekap.csv`, markdown: `presensi_${ym}_rekap.md` };
}

/**
 * Semua bahan ekspor satu bulan, persis seperti ExportPanel.buildAll() Android:
 * rekap hanya karyawan aktif; detail CSV & Markdown memakai semua karyawan untuk nama.
 */
export function buildExports({ ym, today, siteId, employees, records, settings, now = new Date() }) {
  const config = bonusConfig(settings);
  const recaps = recap({
    ym,
    today,
    employees: employees.filter((e) => e.active),
    records,
    workDayOfWeeks: settings.workDays,
    holidays: holidayDates(settings),
    config,
  });
  const place = placeWord(siteId);
  return {
    recaps,
    detail: detailCsv(ym, employees, records, place),
    summary: summaryCsv(ym, recaps),
    markdown: markdown(ym, recaps, records, employees, config, rulesText(settings), generatedAtLabel(now), place),
  };
}

// ---------------------------------------------------------------- tab "Hari ini"

/** Status hari kerja untuk satu tanggal: libur / bukan hari kerja / hari kerja. */
export function dayInfo(dateStr, settings) {
  const holiday = settings.holidays.find((h) => h.date === dateStr) ?? null;
  const isWorkDay = settings.workDays.includes(isoDayOfWeek(dateStr)) && holiday == null;
  return { holiday, isWorkDay };
}

/**
 * Status satu karyawan hari ini (teks sama dengan kartu Beranda Android).
 * "Belum absen" menjadi merah (overdue) bila hari kerja dan jam sekarang sudah lewat jam masuk + toleransi gendernya.
 * @returns {{kind: string, text: string, overdue: boolean}}
 */
export function todayStatus(employee, record, settings, nowTime, isWorkDay) {
  if (!record) {
    const overdue = Boolean(isWorkDay) && lateMinutes(nowTime, workStartFor(settings, employee.gender), toleranceFor(settings, employee.gender)) > 0;
    return { kind: 'BELUM', text: 'Belum absen', overdue };
  }
  const hhmm = String(record.timeIn ?? '').slice(0, 5);
  switch (record.status) {
    case STATUS.TEPAT:
      return { kind: STATUS.TEPAT, text: `Tepat · ${hhmm}`, overdue: false };
    case STATUS.TELAT:
      return { kind: STATUS.TELAT, text: `Telat ${record.lateMinutes} mnt · ${hhmm}`, overdue: false };
    case STATUS.IZIN:
      return { kind: STATUS.IZIN, text: 'Izin', overdue: false };
    case STATUS.SAKIT:
      return { kind: STATUS.SAKIT, text: 'Sakit', overdue: false };
    default:
      return { kind: String(record.status), text: String(record.status), overdue: false };
  }
}

/**
 * Lencana lokasi satu presensi hadir (null untuk izin/sakit).
 * @returns {null|{inLocation: boolean, text: string, distance: string|null, accuracy: string|null, note: string, mapUrl: string|null}}
 */
export function locationBadge(record) {
  if (!record || !isHadir(record.status)) return null;
  const hasPoint = isNum(record.latitude) && isNum(record.longitude);
  return {
    inLocation: Boolean(record.inLocation),
    text: record.inLocation ? 'di lokasi' : 'luar lokasi',
    distance: isNum(record.distanceMeters) ? formatDistance(record.distanceMeters) : null,
    accuracy: isNum(record.accuracy) ? `±${Math.round(record.accuracy)} m` : null,
    note: record.locationNote ?? '',
    mapUrl: hasPoint ? mapsUrl(record.latitude, record.longitude) : null,
  };
}

// ---------------------------------------------------------------- HP presensi

/** HP dianggap bermasalah bila tidak online lebih dari 26 jam (atau belum pernah). */
export const DEVICE_STALE_MS = 26 * 60 * 60 * 1000;

/** "online 5 menit lalu", "online 30 jam lalu", "online 3 hari lalu", "belum pernah online". */
export function lastSeenText(lastSeenMs, nowMs) {
  if (lastSeenMs == null) return 'belum pernah online';
  const min = Math.max(0, Math.floor((nowMs - lastSeenMs) / 60000));
  if (min < 1) return 'online baru saja';
  if (min < 60) return `online ${min} menit lalu`;
  const hours = Math.floor(min / 60);
  if (hours < 48) return `online ${hours} jam lalu`;
  return `online ${Math.floor(hours / 24)} hari lalu`;
}

/**
 * Ringkasan satu HP untuk kartu dashboard.
 * waitingSync = HP belum menerapkan versi pengaturan terbaru (settingsVersion < versi cloud).
 */
export function deviceStatus(device, nowMs, currentSettingsVersion) {
  const last = parseTimestamp(device.lastSeenAt);
  const applied = isNum(device.settingsVersion) ? device.settingsVersion : 0;
  const current = isNum(currentSettingsVersion) ? currentSettingsVersion : 0;
  return {
    lastSeenMs: last,
    lastSeenText: lastSeenText(last, nowMs),
    stale: last == null || nowMs - last > DEVICE_STALE_MS,
    appliedVersion: applied,
    waitingSync: applied < current,
    unsynced: isNum(device.unsynced) ? device.unsynced : null,
    appVersion: typeof device.appVersion === 'string' && device.appVersion !== '' ? device.appVersion : null,
  };
}

/** Sisa waktu kode pasangan "mm:ss" (00:00 bila habis). */
export function countdownText(msLeft) {
  const s = Math.max(0, Math.ceil(msLeft / 1000));
  return `${pad2(Math.floor(s / 60))}:${pad2(s % 60)}`;
}

// ---------------------------------------------------------------- karyawan

/**
 * Data karyawan dari form -> p_employee owner_save_employee. Aturan sama dengan server
 * (nama 1-60 huruf setelah dipangkas, bagian maks 40, gender P/L).
 * @returns {{employee: object}|{error: string, field: string}}
 */
export function employeeFromForm({ id, name, role, gender, active }) {
  const n = String(name ?? '').trim();
  const r = String(role ?? '').trim();
  if ([...n].length < 1) return { error: 'Nama wajib diisi.', field: 'name' };
  if ([...n].length > 60) return { error: 'Nama maks 60 huruf.', field: 'name' };
  if ([...r].length > 40) return { error: 'Bagian maks 40 huruf.', field: 'role' };
  if (gender !== 'P' && gender !== 'L') return { error: 'Pilih gender: Perempuan atau Laki-laki.', field: 'gender' };
  const employee = { name: n, gender, role: r, active: Boolean(active) };
  if (id != null && id !== '') employee.id = Number(id);
  return { employee };
}

/** Kunci owner: 64 karakter heksadesimal. Spasi/baris baru dibuang, huruf dijadikan kecil. null bila tidak cocok. */
export function normalizeOwnerKey(text) {
  const t = String(text ?? '').replace(/\s+/g, '').toLowerCase();
  return /^[0-9a-f]{64}$/.test(t) ? t : null;
}
