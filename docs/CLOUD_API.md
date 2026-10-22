# Kontrak cloud (Supabase) — aplikasi Android + dashboard owner

Proyek Supabase **presensi-karyawan** (`yqgcovruqtfoamjynuyf`, Singapura, paket gratis). Skema lengkap: `supabase/migrations/20261020150000_presensi_core.sql`.

* URL: `https://yqgcovruqtfoamjynuyf.supabase.co`
* Kunci publik (publishable, memang boleh ada di aplikasi/halaman web): `sb_publishable_jhA_daI9x0x0q4Bi3-uesQ_i0qlQm3f`
* **Foto tidak pernah dikirim.** Cloud hanya menyimpan data presensi (karyawan, tanggal, jam, status, menit telat, koordinat, jarak, di lokasi atau tidak).
* Tabel tidak bisa dibaca langsung. Semua akses lewat RPC berikut, masing-masing memeriksa kunci.

## Cara memanggil

```
POST {URL}/rest/v1/rpc/<nama_fungsi>
Header: apikey: <kunci publik>
        Content-Type: application/json
Body:   objek JSON berisi parameter p_* (semua parameter wajib dikirim, boleh null)
```

Jawaban sukses: HTTP 200, isi JSON hasil fungsi. Gagal: HTTP 4xx dengan JSON `{"code": "...", "message": "pesan berbahasa Indonesia", ...}`.
`code = "28000"` (HTTP 401/403) = kunci tidak valid / HP sudah diputus. `code = "22023"` (HTTP 400) = data tidak valid. Tampilkan `message` ke pengguna.

## Kunci

| Kunci | Siapa | Didapat dari |
|---|---|---|
| Kunci owner (64 heksa) | dashboard owner | dibuat sekali oleh Claude, diberikan ke owner; disimpan di browser (localStorage) |
| Kunci HP (64 heksa) | aplikasi Android per HP | `device_pair` dengan kode 8 huruf dari dashboard; disimpan di DataStore aplikasi |

## RPC owner (dashboard)

| Fungsi | Parameter | Hasil |
|---|---|---|
| `owner_sites` | `p_key` | `[{id, name}]` — `egkost` (EG Kost), `indohpl` (Indo HPL) |
| `owner_overview` | `p_key, p_site, p_from (yyyy-mm-dd), p_to` (maks 400 hari) | `{site:{id,name,defaultSettings}, settings:{version,data,updatedAt,updatedBy} \| null, employees:[{id,name,gender,role,active}], attendance:[Attendance], devices:[{id,label,appVersion,unsynced,settingsVersion,lastSeenAt,createdAt}], serverTime}` |
| `owner_save_settings` | `p_key, p_site, p_data (Settings), p_base_version` | `{ok:true, version}` atau `{ok:false, version}` bila `p_base_version` bukan versi terkini (muat ulang lalu ulangi). Kirim `0` bila `settings` masih null. `null` = timpa tanpa cek. |
| `owner_save_employee` | `p_key, p_site, p_employee {id?, name, gender 'P'/'L', role, active}` | karyawan tersimpan; tanpa `id` = karyawan baru (id = terbesar + 1) |
| `owner_create_pairing_code` | `p_key, p_site, p_label` (nama HP, mis. "HP Laundry") | `{code: "K7P2-9XQM", expiresAt}` — berlaku 15 menit, sekali pakai |
| `owner_revoke_device` | `p_key, p_device_id (uuid)` | `true` bila diputus; kunci HP itu langsung ditolak |

## RPC HP presensi (aplikasi Android)

| Fungsi | Parameter | Hasil |
|---|---|---|
| `device_pair` | `p_code` (8 huruf/angka, tanda hubung/spasi/huruf kecil boleh), `p_label` (nama HP), `p_site` (`BuildConfig.FLAVOR`) | `{key, siteId, siteName}`. Ditolak bila kode untuk tempat lain. |
| `device_sync` | `p_key, p_label, p_app_version, p_unsynced (int), p_settings_version (versi yang sudah diterapkan, 0 bila belum)` | `{site, settings \| null, employees, serverTime}` (bentuk sama dengan `owner_overview` tanpa attendance/devices). Juga mencatat "terakhir online" untuk dashboard. |
| `device_upload` | `p_key, p_rows [Attendance tanpa device/uploadedAt] \| null, p_deletes [{employeeId, date}] \| null` | `{upserted, deleted}`. Maks 500 baris. Hapus diproses sebelum simpan. |
| `device_save_settings` | `p_key, p_data (Settings), p_base_version` | `{ok, version}` — `ok:false` = cloud sudah punya versi lebih baru: terapkan versi cloud. |
| `device_seed_employees` | `p_key, p_rows [{id,name,gender,role,active}]` | jumlah yang dimasukkan; hanya bekerja bila cloud belum punya karyawan sama sekali (0 bila sudah ada). |

## Bentuk data

**Attendance** (satu per karyawan per tanggal):

```json
{"employeeId": 1, "date": "2026-10-22", "timeIn": "07:28:10", "timestamp": 1792540090000,
 "status": "TEPAT", "lateMinutes": 0, "note": "",
 "latitude": -6.2001, "longitude": 106.8001, "accuracy": 12.5, "distanceMeters": 15,
 "inLocation": true, "locationNote": "di lokasi kost", "device": "HP Laundry", "uploadedAt": "..."}
```

`status`: `TEPAT`, `TELAT`, `IZIN`, `SAKIT`. `timeIn` kosong untuk IZIN/SAKIT. `timestamp` = epoch milidetik.

**Settings** (`site_settings.data`, divalidasi server; semua kunci opsional):

```json
{
  "workStartFemale": "07:30", "toleranceFemale": 10,
  "workStartMale": "07:30",   "toleranceMale": 10,
  "monthlyBonus": 250000, "lateDeduction": 0, "deductAbsent": true,
  "workDays": [1, 2, 3, 4, 5, 6, 7],
  "storeLat": -6.2, "storeLon": 106.8, "radiusMeters": 100,
  "requireLocation": true, "requireBiometric": true,
  "photoRetentionDays": 60,
  "holidays": [{"date": "2026-12-25", "name": "Natal"}]
}
```

* `workDays`: 1 = Senin … 7 = Minggu (sama dengan `java.time.DayOfWeek.value`).
* `lateDeduction`: 0 = otomatis (uang rajin ÷ jumlah hari kerja bulan itu, dibulatkan).
* `photoRetentionDays`: foto selfie di HP dihapus otomatis setelah N hari (0 = simpan selamanya, selain itu 7–3650). Bawaan 60.
* Bawaan per tempat ada di `site.defaultSettings` (dipakai dashboard bila `settings` masih null).

## Alur sinkron HP (ringkas)

1. Kirim antrean hapus + presensi yang belum terkirim (`device_upload`).
2. Bila pengaturan di HP diubah owner (lewat PIN) dan belum terkirim: `device_save_settings(data, base = versi terakhir yang diterapkan)`. `ok:false` → pengaturan HP ditimpa versi cloud.
3. `device_sync` → bila `settings.version` > versi yang diterapkan: terapkan ke HP (aturan jam, uang rajin, lokasi, hari libur, simpan foto). Bila `settings` null: kirim pengaturan HP (`device_save_settings` base 0).
4. Karyawan: bila cloud kosong → `device_seed_employees(daftar di HP)`; selain itu daftar cloud diterapkan ke HP (tambah/ubah nama, gender, bagian, aktif).
