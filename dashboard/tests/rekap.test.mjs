// Tes logika dashboard: port kasus dari app/src/test/java/com/indohpl/presensi/domain/BonusCalculatorTest.kt
// (BonusCalculatorTest + GeoRuleTest), ditambah kasus EG Kost dan validasi pengaturan (= private.validate_settings).
// Jalankan: node --test "dashboard/tests/*.test.mjs"
import test from 'node:test';
import assert from 'node:assert/strict';
import * as R from '../rekap.js';

const { STATUS } = R;
const monSat = [1, 2, 3, 4, 5, 6];
const sara = { id: 1, name: 'Sara', gender: 'P', role: '', active: true };
const evan = { id: 3, name: 'Evan', gender: 'L', role: '', active: true };
const config = { monthlyBonus: 250000, lateDeductionOverride: 0, deductAbsent: true };
// September 2026: 30 hari, mulai Selasa. Senin–Sabtu = 26 hari kerja.
const sep = '2026-09';

/** Sama dengan rec() di BonusCalculatorTest: jam 08:00:00, tanpa GPS, inLocation = false. */
const rec = (emp, date, status, late = 0) => ({
  employeeId: emp.id, date, timestamp: 0, timeIn: '08:00:00', status, lateMinutes: late, note: '',
  latitude: null, longitude: null, accuracy: null, distanceMeters: null, inLocation: false, locationNote: '',
});

const recapOf = (opts) =>
  R.recap({ ym: sep, workDayOfWeeks: monSat, holidays: new Set(), config, ...opts });

const allDays = (emp, status) => R.workDays(sep, monSat, new Set()).map((d) => rec(emp, d, status(d)));

// ---------------------------------------------------------------- BonusCalculatorTest

test('workDaysSeptember2026: 26 hari kerja Senin–Sabtu, tanpa Minggu', () => {
  const days = R.workDays(sep, monSat, new Set());
  assert.equal(days.length, 26);
  assert.ok(days.every((d) => R.isoDayOfWeek(d) !== 7));
  assert.equal(days[0], '2026-09-01');
  assert.equal(R.isoDayOfWeek('2026-09-01'), 2); // Selasa
});

test('holidayReducesWorkDays', () => {
  assert.equal(R.workDays(sep, monSat, new Set(['2026-09-01'])).length, 25);
});

test('fullMonthOnTimeGetsFullBonus: 250.000 / 26 = 9.615 per hari', () => {
  const [r] = recapOf({ today: '2026-10-05', employees: [sara], records: allDays(sara, () => STATUS.TEPAT) });
  assert.equal(r.hadir, 26);
  assert.equal(r.alpa, 0);
  assert.equal(r.isFullMonth, true);
  assert.equal(r.bonus, 250000);
  assert.equal(r.deductionPerDay, 9615);
});

test('twoLateDaysDeductTwice', () => {
  const days = R.workDays(sep, monSat, new Set());
  const records = days.map((d, i) => rec(sara, d, i < 2 ? STATUS.TELAT : STATUS.TEPAT, i < 2 ? 15 : 0));
  const [r] = recapOf({ today: '2026-10-05', employees: [sara], records });
  assert.equal(r.telat, 2);
  assert.equal(r.deductedDays, 2);
  assert.equal(r.totalDeduction, 2 * 9615);
  assert.equal(r.bonus, 250000 - 2 * 9615);
  assert.equal(r.totalLateMinutes, 30);
  assert.equal(r.isFullMonth, false);
});

test('fixedDeductionOverrideIsUsed: potongan tetap 10.000', () => {
  const days = R.workDays(sep, monSat, new Set());
  const records = days.map((d, i) => rec(sara, d, i === 0 ? STATUS.TELAT : STATUS.TEPAT));
  const [r] = recapOf({ today: '2026-10-05', employees: [sara], records, config: { ...config, lateDeductionOverride: 10000 } });
  assert.equal(r.deductionPerDay, 10000);
  assert.equal(r.bonus, 240000);
});

test('absentCountsOnlyElapsedWorkDays: hari ini Sabtu 5 Sep = 5 hari kerja lewat', () => {
  const records = [rec(evan, '2026-09-01', STATUS.TEPAT), rec(evan, '2026-09-02', STATUS.TEPAT)];
  const [r] = recapOf({ today: '2026-09-05', employees: [evan], records });
  assert.equal(r.workDaysElapsed, 5);
  assert.equal(r.alpa, 3);
  assert.equal(r.deductedDays, 3);
  assert.equal(r.bonus, 250000 - 3 * 9615);
});

test('excusedDaysAreNotDeducted: izin/sakit tidak dipotong tapi bukan full bulan', () => {
  const records = [
    rec(evan, '2026-09-01', STATUS.TEPAT),
    rec(evan, '2026-09-02', STATUS.IZIN),
    rec(evan, '2026-09-03', STATUS.SAKIT),
    rec(evan, '2026-09-04', STATUS.TEPAT),
    rec(evan, '2026-09-05', STATUS.TEPAT),
  ];
  const [r] = recapOf({ today: '2026-09-05', employees: [evan], records });
  assert.equal(r.izin, 1);
  assert.equal(r.sakit, 1);
  assert.equal(r.alpa, 0);
  assert.equal(r.bonus, 250000);
  assert.equal(r.isFullMonth, false);
});

test('absentNotDeductedWhenDisabled', () => {
  const [r] = recapOf({ today: '2026-09-05', employees: [evan], records: [], config: { ...config, deductAbsent: false } });
  assert.equal(r.alpa, 5);
  assert.equal(r.deductedDays, 0);
  assert.equal(r.bonus, 250000);
});

test('bonusNeverNegative: potongan dibatasi uang rajin penuh', () => {
  const [r] = recapOf({
    today: '2026-10-05', employees: [sara], records: allDays(sara, () => STATUS.TELAT),
    config: { ...config, lateDeductionOverride: 100000 },
  });
  assert.equal(r.bonus, 0);
  assert.equal(r.totalDeduction, 250000);
});

test('genderRulesFromUser: P 07:45 +10, L 08:00 +5', () => {
  assert.equal(R.lateMinutes('07:55:00', '07:45', 10), 0);
  assert.equal(R.lateMinutes('07:56:00', '07:45', 10), 11);
  assert.equal(R.lateMinutes('08:05:00', '08:00', 5), 0);
  assert.equal(R.lateMinutes('08:06:00', '08:00', 5), 6);
});

test('kostRuleHalfPastSevenWithTenMinutes: EG Kost 07:30 +10 -> batas 07:40:00', () => {
  assert.equal(R.lateMinutes('07:29:00', '07:30', 10), 0);
  assert.equal(R.lateMinutes('07:40:00', '07:30', 10), 0);
  assert.equal(R.lateMinutes('07:40:01', '07:30', 10), 10);
  assert.equal(R.lateMinutes('07:41:00', '07:30', 10), 11);
  assert.equal(R.lateMinutes('08:15:00', '07:30', 10), 45);
});

test('rulesTextCollapsesWhenBothGendersShareOneRule', () => {
  const same = R.normalizeSettings({ workStartFemale: '07:30', toleranceFemale: 10, workStartMale: '07:30', toleranceMale: 10 });
  assert.equal(R.sameRuleForAll(same), true);
  assert.equal(R.rulesText(same), '07:30 (toleransi 10 mnt)');
  assert.equal(R.shortRulesText(same), '07:30 (+10 mnt)');
  const diff = R.normalizeSettings({ workStartFemale: '07:45', toleranceFemale: 10, workStartMale: '08:00', toleranceMale: 5 });
  assert.equal(R.sameRuleForAll(diff), false);
  assert.equal(R.rulesText(diff), 'Perempuan 07:45 (toleransi 10 mnt) · Laki-laki 08:00 (toleransi 5 mnt)');
  assert.equal(R.shortRulesText(diff), 'P 07:45 (+10 mnt) · L 08:00 (+5 mnt)');
});

test('employeeLabelIncludesRoleOnlyWhenSet', () => {
  assert.equal(R.employeeLabel({ id: 1, name: 'Sara', gender: 'P', role: '' }), 'Sara');
  assert.equal(R.employeeLabel({ id: 1, name: 'Sara', gender: 'P', role: '  ' }), 'Sara');
  assert.equal(R.employeeLabel({ id: 1, name: 'Inul', gender: 'P', role: 'Laundry' }), 'Inul · Laundry');
});

test('sevenDayWorkWeek: 30 hari kerja -> 8.333 per hari', () => {
  assert.equal(R.workDays(sep, [1, 2, 3, 4, 5, 6, 7], new Set()).length, 30);
  assert.equal(R.deductionPerDay(config, 30), 8333);
  assert.equal(R.deductionPerDay(config, 0), 0);
  assert.equal(R.deductionPerDay({ ...config, lateDeductionOverride: 10000 }, 30), 10000);
});

test('lateRuleRespectsTolerance', () => {
  assert.equal(R.lateMinutes('07:59:59', '08:00', 0), 0);
  assert.equal(R.lateMinutes('08:00:00', '08:00', 0), 0);
  assert.equal(R.lateMinutes('08:00:01', '08:00', 0), 1);
  assert.equal(R.lateMinutes('08:05:00', '08:00', 5), 0);
  assert.equal(R.lateMinutes('08:06:00', '08:00', 5), 6);
  assert.equal(R.lateMinutes('08:20:30', '08:00', 0), 20);
  // Jam masuk tidak terbaca -> 08:00 (LateRule.parseWorkStart ?: 08:00).
  assert.equal(R.lateMinutes('08:10:00', '8:00', 0), 10);
});

test('csvAndMarkdownContainNamesAndTotals', () => {
  const records = [rec(sara, '2026-09-01', STATUS.TELAT, 12)];
  const recaps = recapOf({ today: '2026-09-01', employees: [sara], records });
  const csv = R.detailCsv(sep, [sara], records, 'toko');
  assert.ok(csv.startsWith('tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_toko_m,di_lokasi,ket_lokasi\n'));
  assert.ok(csv.includes('2026-09-01,Sara,TELAT,08:00:00,12,,'));
  const md = R.markdown(sep, recaps, records, [sara], config,
    'Perempuan 07:45 (toleransi 10 mnt) · Laki-laki 08:00 (toleransi 5 mnt)', '01/09/2026 09:00:00', 'toko');
  assert.ok(md.includes('# Rekap Presensi September 2026'));
  assert.ok(md.includes('| Sara | 26 |'));
  assert.ok(md.includes('| Jarak toko |'));
  // Varian kost: nama kolom ikut kata tempatnya, dan bagian kerja ikut tampil di ringkasan.
  const inul = { id: 1, name: 'Inul', gender: 'P', role: 'Laundry', active: true };
  const kostCsv = R.detailCsv(sep, [inul], [], 'kost');
  assert.ok(kostCsv.includes(',jarak_kost_m,'));
  const kostMd = R.markdown(sep, recapOf({ today: '2026-09-01', employees: [inul], records: [] }), [], [inul], config,
    '07:30 (toleransi 10 mnt)', '01/09/2026 09:00:00', 'kost');
  assert.ok(kostMd.includes('| Inul · Laundry | 26 |'));
  assert.ok(kostMd.includes('| Jarak kost |'));
  assert.equal(R.rupiah(250000), 'Rp 250.000');
  assert.equal(R.rupiah(9615), 'Rp 9.615');
  assert.equal(R.rupiah(0), 'Rp 0');
  assert.equal(R.rupiah(100000000), 'Rp 100.000.000');
});

// ---------------------------------------------------------------- GeoRuleTest (bagian yang dipakai dashboard)

test('parseAndFormat', () => {
  assert.deepEqual(R.parseLatLon('-6.2, 106.816666'), [-6.2, 106.816666]);
  assert.deepEqual(R.parseLatLon('-6.2 106.816666'), [-6.2, 106.816666]);
  assert.equal(R.parseLatLon('abc'), null);
  assert.equal(R.parseLatLon('95, 10'), null);
  assert.equal(R.formatCoord(-6.175392, 106.827153), '-6.175392, 106.827153');
  assert.equal(R.formatDistance(35), '35 m');
  assert.equal(R.formatDistance(1234), '1,2 km');
});

test('outsideLocationCountedInRecap', () => {
  const emp = { id: 1, name: 'Sara', gender: 'P', role: '', active: true };
  const base = { employeeId: 1, timestamp: 0, timeIn: '07:50:00', status: STATUS.TEPAT, lateMinutes: 0, note: '', latitude: null, longitude: null, accuracy: null, distanceMeters: null };
  const records = [
    { ...base, date: '2026-09-01', inLocation: true, locationNote: '' },
    { ...base, date: '2026-09-02', inLocation: false, locationNote: 'di luar lokasi' },
  ];
  const [r] = recapOf({ today: '2026-09-02', employees: [emp], records });
  assert.equal(r.outsideLocation, 1);
  const csv = R.detailCsv(sep, [emp], records, 'toko');
  assert.ok(csv.includes('2026-09-02,Sara,TEPAT,07:50:00,0,,,,,,,TIDAK,di luar lokasi'));
});

test('format angka mengikuti Java (HALF_UP, Double.toString)', () => {
  // toFixed membulatkan nilai biner (1.15 -> "1.1"); Java String.format HALF_UP -> "1,2 km".
  assert.equal(R.formatDistance(1150), '1,2 km');
  assert.equal(R.formatDistance(1050), '1,1 km');
  assert.equal(R.formatDistance(999), '999 m');
  assert.equal(R.formatDistance(12345), '12,3 km');
  assert.equal(R.formatFixed(-6.2, 6), '-6.200000');
  assert.equal(R.formatFixed(106.8000005, 6), '106.800001');
  assert.equal(R.formatFixed(0.0000005, 6), '0.000001');
  assert.equal(R.formatFixed(9.9999999, 6), '10.000000');
  assert.equal(R.javaDoubleToString(-6.2001), '-6.2001');
  assert.equal(R.javaDoubleToString(106), '106.0');
  assert.equal(R.javaDoubleToString(0.0005), '5.0E-4');
  assert.equal(R.javaDoubleToString(12345678.5), '1.23456785E7');
});

// ---------------------------------------------------------------- EG Kost

const egkostDefaults = {
  workStartFemale: '07:30', toleranceFemale: 10, workStartMale: '07:30', toleranceMale: 10, monthlyBonus: 250000,
  lateDeduction: 0, deductAbsent: true, workDays: [1, 2, 3, 4, 5, 6, 7], storeLat: null, storeLon: null, radiusMeters: 100,
  requireLocation: true, requireBiometric: true, photoRetentionDays: 60, holidays: [],
};
const inul = { id: 1, name: 'Inul', gender: 'P', role: 'Laundry', active: true };
const bikNia = { id: 2, name: 'Bik Nia', gender: 'P', role: 'Kos', active: true };

test('EG Kost: aturan 07:30 (+10) tampil sebagai satu aturan', () => {
  const s = R.normalizeSettings(egkostDefaults, null); // settings cloud masih null -> bawaan tempat
  assert.equal(R.sameRuleForAll(s), true);
  assert.equal(R.rulesText(s), '07:30 (toleransi 10 mnt)');
  assert.equal(R.shortRulesText(s), '07:30 (+10 mnt)');
  assert.equal(R.limitLabel(s, 'P'), '07:40');
  assert.equal(R.placeWord('egkost'), 'kost');
  assert.equal(R.placeWord('indohpl'), 'toko');
});

test('EG Kost: label "Inul · Laundry", kolom jarak_kost_m, foto kosong (cloud tanpa foto)', () => {
  const s = R.normalizeSettings(egkostDefaults, {});
  const records = [
    { employeeId: 1, date: '2026-10-01', timeIn: '07:41:00', timestamp: 1790815260000, status: 'TELAT', lateMinutes: 11, note: '',
      latitude: -6.2001, longitude: 106.8001, accuracy: 12.5, distanceMeters: 15, inLocation: true, locationNote: 'di lokasi kost',
      device: 'HP Laundry', uploadedAt: '2026-10-01T00:41:05.123456+00:00' },
    { employeeId: 2, date: '2026-10-01', timeIn: '07:20:05', timestamp: 1790814005000, status: 'TEPAT', lateMinutes: 0, note: 'lewat, pintu "B"',
      latitude: -6.21, longitude: 106.81, accuracy: 30, distanceMeters: 1150, inLocation: false, locationNote: 'di luar lokasi',
      device: 'HP Laundry', uploadedAt: '2026-10-01T00:20:10+00:00' },
  ];
  const ex = R.buildExports({ ym: '2026-10', today: '2026-10-01', siteId: 'egkost', employees: [inul, bikNia], records, settings: s,
    now: new Date(2026, 9, 1, 9, 0, 0) });
  const lines = ex.detail.split('\n');
  assert.equal(lines[0], 'tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_kost_m,di_lokasi,ket_lokasi');
  // Urut tanggal lalu timestamp: Bik Nia (07:20) sebelum Inul (07:41). Catatan berkoma/berkutip di-quote seperti csvCell.
  assert.equal(lines[1], '2026-10-01,Bik Nia,TEPAT,07:20:05,0,"lewat, pintu ""B""",,-6.21,106.81,30,1150,TIDAK,di luar lokasi');
  assert.equal(lines[2], '2026-10-01,Inul,TELAT,07:41:00,11,,,-6.2001,106.8001,12,15,YA,di lokasi kost');
  assert.ok(ex.markdown.includes('| Inul · Laundry | 31 | 1 | 0 | 1 | 0 | 0 | 0 | 0 | Rp 8.065 | **Rp 241.935** |'));
  assert.ok(ex.markdown.includes('| Bik Nia · Kos | 31 | 1 | 1 | 0 | 0 | 0 | 0 | 1 | Rp 0 | **Rp 250.000** |'));
  assert.ok(ex.markdown.includes('| Jarak kost | Koordinat | Catatan |'));
  assert.ok(ex.markdown.includes('| 2026-10-01 | Bik Nia | TEPAT | 07:20:05 | 0 | DI LUAR (di luar lokasi) | 1,2 km | -6.210000, 106.810000 | lewat, pintu "B" |'));
  assert.ok(ex.markdown.includes('Dibuat: 01/10/2026 09:00:00\nJam masuk: 07:30 (toleransi 10 mnt). Uang rajin penuh: Rp 250.000/bulan. Potongan per hari telat: Rp 8.065 (alpa juga dipotong).'));
  assert.equal(ex.summary.split('\n')[1], '2026-10,Inul,31,1,0,1,0,0,0,0,11,8065,1,8065,241935,TIDAK');
  assert.deepEqual(R.exportFileNames('2026-10'), {
    detail: 'presensi_2026-10_detail.csv', summary: 'presensi_2026-10_rekap.csv', markdown: 'presensi_2026-10_rekap.md',
  });
});

test('rekap hanya karyawan aktif; detail tetap memakai nama karyawan nonaktif', () => {
  const old = { id: 9, name: 'Lama', gender: 'L', role: '', active: false };
  const s = R.normalizeSettings(egkostDefaults);
  const records = [rec(old, '2026-09-01', STATUS.TEPAT)];
  const ex = R.buildExports({ ym: sep, today: '2026-09-30', siteId: 'egkost', employees: [inul, old], records, settings: s });
  assert.deepEqual(ex.recaps.map((r) => r.employee.name), ['Inul']);
  assert.ok(ex.detail.includes('2026-09-01,Lama,TEPAT'));
});

// ---------------------------------------------------------------- Hari ini & HP

test('Hari ini: teks status, "Belum absen" merah setelah batas gender, hanya di hari kerja', () => {
  const s = R.normalizeSettings({ workStartFemale: '07:45', toleranceFemale: 10, workStartMale: '08:00', toleranceMale: 5 });
  const tepat = { status: 'TEPAT', timeIn: '07:28:10', lateMinutes: 0 };
  const telat = { status: 'TELAT', timeIn: '08:11:00', lateMinutes: 11 };
  assert.equal(R.todayStatus(sara, tepat, s, '09:00:00', true).text, 'Tepat · 07:28');
  assert.equal(R.todayStatus(evan, telat, s, '09:00:00', true).text, 'Telat 11 mnt · 08:11');
  assert.equal(R.todayStatus(sara, { status: 'IZIN', timeIn: '' }, s, '09:00:00', true).text, 'Izin');
  assert.equal(R.todayStatus(sara, { status: 'SAKIT', timeIn: '' }, s, '09:00:00', true).text, 'Sakit');
  // Perempuan batas 07:55:00, laki-laki 08:05:00.
  assert.equal(R.todayStatus(sara, null, s, '07:55:00', true).overdue, false);
  assert.equal(R.todayStatus(sara, null, s, '07:55:01', true).overdue, true);
  assert.equal(R.todayStatus(evan, null, s, '08:00:00', true).overdue, false);
  assert.equal(R.todayStatus(evan, null, s, '08:05:01', true).overdue, true);
  assert.equal(R.todayStatus(evan, null, s, '10:00:00', false).overdue, false); // libur / bukan hari kerja
  assert.equal(R.todayStatus(evan, null, s, '10:00:00', true).text, 'Belum absen');
});

test('Hari ini: hari libur dan hari bukan kerja', () => {
  const s = R.normalizeSettings({ workDays: [1, 2, 3, 4, 5, 6], holidays: [{ date: '2026-12-25', name: 'Natal' }] });
  assert.deepEqual(R.dayInfo('2026-12-25', s), { holiday: { date: '2026-12-25', name: 'Natal' }, isWorkDay: false });
  assert.equal(R.dayInfo('2026-10-25', s).isWorkDay, false); // Minggu
  assert.equal(R.dayInfo('2026-10-22', s).isWorkDay, true); // Kamis
  assert.equal(R.longDateLabel('2026-10-22'), 'Kamis, 22 Oktober 2026');
  assert.equal(R.shortDateLabel('2026-09-01'), 'Sel 1 Sep');
});

test('lencana lokasi: di lokasi / luar lokasi + jarak + akurasi + tautan peta', () => {
  const inside = R.locationBadge({ status: 'TEPAT', latitude: -6.2001, longitude: 106.8001, accuracy: 12.5, distanceMeters: 15, inLocation: true, locationNote: 'di lokasi kost' });
  assert.equal(inside.text, 'di lokasi');
  assert.equal(inside.distance, '15 m');
  assert.equal(inside.accuracy, '±13 m');
  assert.equal(inside.mapUrl, 'https://www.google.com/maps?q=-6.2001,106.8001');
  const out = R.locationBadge({ status: 'TELAT', latitude: null, longitude: null, accuracy: null, distanceMeters: null, inLocation: false, locationNote: 'GPS tidak didapat' });
  assert.equal(out.text, 'luar lokasi');
  assert.equal(out.mapUrl, null);
  assert.equal(R.locationBadge({ status: 'IZIN' }), null);
});

test('HP: terakhir online, peringatan > 26 jam, menunggu sinkron pengaturan', () => {
  const now = Date.parse('2026-10-22T03:00:00Z');
  const d = R.deviceStatus({ lastSeenAt: '2026-10-21T20:30:00.654321+00:00', settingsVersion: 3, unsynced: 2, appVersion: '1.2.150' }, now, 4);
  assert.equal(d.lastSeenText, 'online 6 jam lalu');
  assert.equal(d.stale, false);
  assert.equal(d.waitingSync, true);
  assert.equal(d.unsynced, 2);
  const old = R.deviceStatus({ lastSeenAt: '2026-10-20T21:00:00+00:00', settingsVersion: 4, unsynced: 0, appVersion: null }, now, 4);
  assert.equal(old.lastSeenText, 'online 30 jam lalu');
  assert.equal(old.stale, true);
  assert.equal(old.waitingSync, false);
  const never = R.deviceStatus({ lastSeenAt: null, settingsVersion: null }, now, 0);
  assert.equal(never.lastSeenText, 'belum pernah online');
  assert.equal(never.stale, true);
  assert.equal(never.waitingSync, false);
  assert.equal(R.lastSeenText(now - 5 * 60000, now), 'online 5 menit lalu');
  assert.equal(R.lastSeenText(now - 20000, now), 'online baru saja');
  assert.equal(R.lastSeenText(now - 72 * 3600000, now), 'online 3 hari lalu');
  assert.equal(R.countdownText(14 * 60000 + 59500), '15:00');
  assert.equal(R.countdownText(-5), '00:00');
});

test('tanggal lokal tidak memakai UTC', () => {
  const d = new Date(2026, 9, 22, 0, 30, 0); // 00:30 waktu lokal
  assert.equal(R.localDateString(d), '2026-10-22');
  assert.equal(R.localTimeString(d), '00:30:00');
  assert.equal(R.addMonths('2026-01', -1), '2025-12');
  assert.equal(R.addMonths('2026-12', 1), '2027-01');
  assert.deepEqual(R.monthRange('2028-02'), { from: '2028-02-01', to: '2028-02-29' });
  assert.equal(R.monthLabel('2026-09'), 'September 2026');
  assert.equal(R.isValidDate('2026-02-29'), false);
  assert.equal(R.isValidDate('2028-02-29'), true);
  assert.equal(R.parseTimestamp('2026-10-22T07:28:10.123456+00:00'), Date.UTC(2026, 9, 22, 7, 28, 10, 123));
  assert.equal(R.parseTimestamp('2026-10-22T14:28:10+07'), Date.UTC(2026, 9, 22, 7, 28, 10));
  assert.equal(R.parseTimestamp(null), null);
});

// ---------------------------------------------------------------- pengaturan (= private.validate_settings)

test('validateSettings: sama dengan server', () => {
  assert.equal(R.validateSettings(egkostDefaults), null);
  assert.equal(R.validateSettings({}), null); // semua kunci opsional
  assert.match(R.validateSettings([]), /objek JSON/);
  assert.match(R.validateSettings({ workStartFemale: '7:30' }), /Jam masuk perempuan/);
  assert.match(R.validateSettings({ workStartMale: '24:00' }), /Jam masuk laki-laki/);
  assert.equal(R.validateSettings({ workStartMale: '23:59' }), null);
  assert.match(R.validateSettings({ toleranceFemale: 241 }), /Toleransi perempuan/);
  assert.match(R.validateSettings({ toleranceMale: '5' }), /Toleransi laki-laki/);
  assert.equal(R.validateSettings({ toleranceMale: 240 }), null);
  assert.match(R.validateSettings({ monthlyBonus: 100000001 }), /Uang rajin/);
  assert.match(R.validateSettings({ lateDeduction: -1 }), /Potongan/);
  assert.match(R.validateSettings({ workDays: [] }), /minimal satu hari kerja/);
  assert.match(R.validateSettings({ workDays: [0, 1] }), /1 \(Senin\) sampai 7 \(Minggu\)/);
  assert.match(R.validateSettings({ workDays: [1, 2, 3, 4, 5, 6, 7, 1] }), /Hari kerja/);
  assert.match(R.validateSettings({ radiusMeters: 9 }), /Radius minimal 10/);
  assert.match(R.validateSettings({ radiusMeters: 100001 }), /Radius/);
  assert.equal(R.validateSettings({ photoRetentionDays: 0 }), null);
  assert.match(R.validateSettings({ photoRetentionDays: 6 }), /Simpan foto/);
  assert.match(R.validateSettings({ photoRetentionDays: 3651 }), /Simpan foto/);
  assert.equal(R.validateSettings({ photoRetentionDays: 7 }), null);
  assert.equal(R.validateSettings({ storeLat: null, storeLon: null }), null);
  assert.match(R.validateSettings({ storeLat: 91 }), /Lintang/);
  assert.match(R.validateSettings({ storeLon: -181 }), /Bujur/);
  assert.match(R.validateSettings({ requireLocation: 'ya' }), /requireLocation harus true\/false/);
  assert.match(R.validateSettings({ holidays: [{ date: '2026-1-01', name: 'x' }] }), /Hari libur/);
  assert.match(R.validateSettings({ holidays: [{ date: '2026-01-01', name: 'x'.repeat(61) }] }), /Hari libur/);
  assert.match(R.validateSettings({ holidays: new Array(401).fill({ date: '2026-01-01', name: '' }) }), /maks 400/);
  const big = { holidays: Array.from({ length: 400 }, (_, i) => ({ date: `2026-01-${String((i % 28) + 1).padStart(2, '0')}`, name: 'x'.repeat(60) })) };
  assert.match(R.validateSettings(big), /terlalu besar/);
});

const formOf = (over = {}) => ({
  workStartFemale: '07:30', toleranceFemale: '10', workStartMale: '07:30', toleranceMale: '10', monthlyBonus: '250.000',
  lateDeduction: '0', deductAbsent: true, workDays: [7, 1, 2, 3, 4, 5, 6], location: '-6.200000, 106.816666', radiusMeters: '100',
  requireLocation: true, requireBiometric: false, photoRetentionDays: '60', holidays: [{ date: '2026-12-25', name: 'Natal' }, { date: '2026-08-17', name: 'HUT RI' }],
  ...over,
});

test('settingsFromForm: parse seperti layar Pengaturan Android, lalu validasi server', () => {
  const { data } = R.settingsFromForm(formOf(), { futureKey: 1, monthlyBonus: 1 });
  assert.equal(data.futureKey, 1); // kunci lain dipertahankan
  assert.equal(data.monthlyBonus, 250000);
  assert.deepEqual(data.workDays, [1, 2, 3, 4, 5, 6, 7]);
  assert.equal(data.storeLat, -6.2);
  assert.equal(data.storeLon, 106.816666);
  assert.equal(data.requireBiometric, false);
  assert.deepEqual(data.holidays.map((h) => h.date), ['2026-08-17', '2026-12-25']);
  assert.equal(R.validateSettings(data), null);
  assert.equal(R.settingsFromForm(formOf({ location: '' })).data.storeLat, null);
  assert.equal(R.settingsFromForm(formOf({ location: 'https://www.google.com/maps/@-6.2,106.8166,17z' })).data.storeLon, 106.8166);
  assert.equal(R.settingsFromForm(formOf({ workStartFemale: '7.30' })).field, 'workStartFemale');
  assert.equal(R.settingsFromForm(formOf({ toleranceMale: '-1' })).field, 'toleranceMale');
  assert.equal(R.settingsFromForm(formOf({ toleranceMale: '300' })).field, 'toleranceMale'); // ditolak validasi server (0-240)
  assert.equal(R.settingsFromForm(formOf({ monthlyBonus: '250,000' })).field, 'monthlyBonus');
  assert.equal(R.settingsFromForm(formOf({ workDays: [] })).field, 'workDays');
  assert.equal(R.settingsFromForm(formOf({ location: 'Jl. Merdeka' })).field, 'location');
  assert.equal(R.settingsFromForm(formOf({ radiusMeters: '5' })).field, 'radiusMeters');
  assert.equal(R.settingsFromForm(formOf({ photoRetentionDays: '3' })).field, 'photoRetentionDays');
});

test('settingsDiff: daftar perbedaan untuk bentrok versi', () => {
  const a = R.normalizeSettings(egkostDefaults);
  const b = R.normalizeSettings(egkostDefaults, { toleranceFemale: 15, storeLat: -6.2, storeLon: 106.8 });
  assert.deepEqual(R.settingsDiff(a, b), ['toleranceFemale', 'location']);
  assert.equal(R.settingValueText('location', b), '-6.200000, 106.800000');
  assert.equal(R.settingValueText('lateDeduction', a), 'otomatis');
  assert.equal(R.settingValueText('workDays', a), 'Sen, Sel, Rab, Kam, Jum, Sab, Min');
});

test('karyawan & kunci owner', () => {
  assert.deepEqual(R.employeeFromForm({ name: '  Inul ', role: ' Laundry ', gender: 'P', active: true }), {
    employee: { name: 'Inul', gender: 'P', role: 'Laundry', active: true },
  });
  assert.equal(R.employeeFromForm({ id: 2, name: 'Bik Nia', role: '', gender: 'P', active: false }).employee.id, 2);
  assert.equal(R.employeeFromForm({ name: ' ', role: '', gender: 'P' }).field, 'name');
  assert.equal(R.employeeFromForm({ name: 'A', role: 'x'.repeat(41), gender: 'P' }).field, 'role');
  assert.equal(R.employeeFromForm({ name: 'A', role: '', gender: 'X' }).field, 'gender');
  const key = 'ab'.repeat(32);
  assert.equal(R.normalizeOwnerKey(` ${key.toUpperCase()}\n`), key);
  assert.equal(R.normalizeOwnerKey('abc'), null);
});
