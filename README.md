# Presensi Indo HPL

Aplikasi Android untuk mencatat presensi (jam masuk) karyawan di **HP kasir**.
Setiap presensi diverifikasi dengan **biometrik HP** lalu **selfie berstempel waktu + koordinat GPS**, dan hanya sah bila HP berada di **lokasi toko Indo HPL**.
Aplikasi menghitung **uang rajin** bulanan otomatis dan bisa mengekspor rekap untuk ditempel ke Claude.

## Karyawan

| Nama    | Gender |
|---------|--------|
| Sara    | P      |
| Riyanti | P      |
| Evan    | L      |
| Madi    | L      |
| Roni    | L      |

Daftar ini di-seed otomatis saat aplikasi pertama kali dibuka (`Repository.ensureSeeded`).

## Alur presensi

```
Beranda ──tap "Absen"──▶ [1] Biometrik HP kasir ──▶ [2] Selfie (kamera depan) + GPS
                                                        │
                                                        ▼
                     Cek jarak HP ke titik toko (radius default 100 m, fake GPS ditolak)
                                                        │
                                                        ▼
        Foto distempel: nama + tanggal/jam + koordinat + "Di lokasi Indo HPL · 35 m" + "Presensi Indo HPL"
                                                        │
                                                        ▼
                             Simpan ▶ status TEPAT / TELAT (menit) ▶ kembali ke Beranda
```

* Satu karyawan hanya bisa absen **sekali per hari** (dijaga di database).
* Status **telat** = jam foto lebih dari *jam masuk + toleransi*. Aturan per gender:

  | Gender | Jam masuk | Toleransi | Batas dianggap tepat |
  |---|---|---|---|
  | Perempuan (Sara, Riyanti) | 07:45 | 10 menit | 07:55:00 |
  | Laki-laki (Evan, Madi, Roni) | 08:00 | 5 menit | 08:05:00 |

  Menit telat dihitung dari jam masuk (perempuan datang 07:56 = telat 11 menit).
* Hanya **jam masuk** yang dicatat, tidak ada check-out.
* Kasir bisa menandai **Izin** / **Sakit** dari menu ⋮ di kartu karyawan (tanpa selfie, tidak dipotong).
* Menu ⋮ juga bisa **Lihat foto** dan **Hapus catatan hari ini** (untuk salah tekan).

## Lokasi toko (GPS)

1. Buka **Pengaturan → Lokasi toko Indo HPL**, berdiri di dalam toko, tekan **Pakai lokasi HP sekarang**, lalu **Simpan pengaturan**. Atau ketik koordinat dari Google Maps (tekan lama titik toko → salin angka `-6.xxxxxx, 106.xxxxxx`).
2. Saat absen, aplikasi mencari posisi GPS. Tombol jepret hanya aktif bila HP di dalam **radius** (default 100 m, akurasi GPS ikut diperhitungkan).
3. Koordinat, jarak ke toko, dan status "Di lokasi / DI LUAR LOKASI" dibakar ke foto dan disimpan di database + CSV.
4. Saklar **Wajib di lokasi toko** bisa dimatikan: absen tetap tercatat tapi ditandai "Luar lokasi" (merah) di Beranda, Rekap, dan ekspor.
5. Lokasi palsu (aplikasi fake GPS) terdeteksi dan ditolak.

## Aturan uang rajin: saldo yang bertambah (bisa diubah di Pengaturan)

Saldo uang rajin tiap karyawan **mulai dari Rp 0 di awal bulan dan bertambah** setiap hari kerja yang dihitung. Tampil hijau dengan tanda "+".

| Hari kerja | Tambahan saldo |
|---|---|
| Tepat waktu | **+Rp 8.333** (250.000 ÷ jumlah hari kerja bulan itu) |
| Izin / sakit | +Rp 8.333 (sama seperti aturan lama: tidak dipotong) |
| Telat | +Rp 0 |
| Alpa (tidak absen tanpa izin/sakit) | +Rp 0 (bisa diubah jadi tetap menambah) |
| Absen di hari libur toko | tidak dihitung |

Rumus: `saldo = bulat(250.000 × hari_menambah ÷ hari_kerja)`, jadi sebulan penuh tepat waktu = **tepat Rp 250.000**. Hasil akhir bulan sama dengan aturan lama (selisih pembulatan paling banyak beberapa rupiah).

| Parameter | Default |
|---|---|
| Uang rajin maksimal per bulan | Rp 250.000 |
| Tambahan per hari tepat waktu | 0 = otomatis (dibagi rata). Kalau diisi angka tetap, saldo tetap tidak melebihi maksimal |
| Hari kerja | Senin–Minggu, kecuali tanggal libur toko |

Tempat melihat saldo:
- **Beranda**: setelah absen muncul pil hijau "+Rp 8.333" dan saldo bulan ini; sebelum absen ada petunjuk "Tepat waktu sampai 07:55 = +Rp 8.333".
- **Layar hasil absen**: "+Rp 8.333" besar dan saldo yang menghitung naik, bilah kemajuan, dan 🔥 hari beruntun.
- **Tab Saldo**: saldo per karyawan, bilah menuju maksimal, "masih bisa sampai", dan riwayat harian dengan tambahan per hari.

## Hak akses: kasir vs owner

| Tab | Siapa | Isi |
|---|---|---|
| Beranda | kasir | absen, tandai izin/sakit, lihat foto, hapus salah tekan |
| Saldo | kasir & karyawan & owner | **hanya melihat** saldo uang rajin per bulan, tidak ada tombol ubah/ekspor |
| Pengaturan | **owner, dikunci PIN** | aturan jam, uang rajin, lokasi toko, hari libur, ekspor rekap, ganti PIN |

PIN owner (4–8 angka) dibuat saat tab Pengaturan pertama kali dibuka. Disimpan sebagai hash (tidak bisa dibaca dari file). Salah 5 kali = terkunci 30 detik. Pindah tab = terkunci lagi. PIN yang lupa hanya bisa direset dengan menghapus data aplikasi.

## Pembersihan otomatis (self-regulating)

Aplikasi mandiri, tanpa server. Supaya HP tidak penuh dan data lama tidak menumpuk, aplikasi **menghapus sendiri** catatan presensi beserta fotonya yang lebih lama dari batas simpan (bawaan **2 bulan**: bulan berjalan + 2 bulan sebelumnya disimpan, lebih lama dihapus).

| Hari ini | Disimpan | Dihapus |
|---|---|---|
| 29 Oktober 2026 | Agustus, September, Oktober | Juli dan sebelumnya |
| 1 November 2026 | September, Oktober, November | Agustus dan sebelumnya |

Pengecekan berjalan saat aplikasi dibuka dan sekali sehari di latar (WorkManager). Batas bulan bisa diubah owner di Pengaturan (1 sampai 24), termasuk tombol **Bersihkan sekarang**. Ekspor rekap sebelum bulannya terhapus.

Pengaman: hapus **otomatis** ditunda bila absen terakhir lebih dari 35 hari lalu (misalnya jam HP melompat maju atau HP lama tidak dipakai). Pembersihan manual tetap bisa.

## Rekap & ekspor ke Claude

Tab **Saldo** menampilkan per karyawan: saldo uang rajin, hari menambah, tepat, telat, izin, sakit, alpa, dan riwayat harian.
Bulan berjalan ditandai "sementara". Ketuk nama untuk detail harian.

Ekspor ada di **Pengaturan (owner) → Ekspor rekap bulanan**. Tombol **Bagikan file** mengirim 3 file lewat menu bagikan Android (pilih aplikasi Claude, WhatsApp, Drive, dll):

| File | Isi |
|---|---|
| `presensi_YYYY-MM_detail.csv` | satu baris per presensi: tanggal, nama, status, jam masuk, menit telat, catatan, nama file foto, latitude, longitude, akurasi, jarak ke toko, di lokasi (YA/TIDAK) |
| `presensi_YYYY-MM_rekap.csv` | ringkasan per karyawan termasuk uang rajin |
| `presensi_YYYY-MM_rekap.md` | tabel Markdown lengkap, siap tempel ke Claude |

Tombol **Salin teks** menyalin rekap Markdown ke clipboard.

## Cara mendapatkan APK

Cara termudah: buka link ini di browser HP kasir, file APK langsung terunduh:

**https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-indohpl/PresensiIndoHPL.apk**

Link itu selalu berisi build terbaru aplikasi Indo HPL (branch `apk-indohpl`). Branch `apk-build` milik aplikasi **EG Kost** yang dikerjakan di sesi lain, dan tidak disentuh lagi oleh workflow ini.

1. Unduh APK lewat link di atas (atau tab **Actions** → filter Branch `claude/employee-attendance-app-9b9dqg` → run terbaru → **Artifacts** → `PresensiIndoHPL-2.0.x`).

   > **Jangan** pakai link lama `.../raw/apk-build/PresensiIndoHPL.apk` atau artifact bernama `presensi-indo-hpl-debug`. Isinya aplikasi lain dengan nama paket sama. Kalau Android bilang "aplikasi tidak terpasang", **jangan uninstall** aplikasi 2.0.x, karena semua data akan hilang.
2. Ketuk file APK di HP kasir, izinkan "instal dari sumber tidak dikenal", instal.
3. Buka aplikasi → beri izin kamera dan lokasi saat diminta. Nyalakan **Lokasi** di HP (mode akurasi tinggi).

### Kunci tanda tangan permanen (penting untuk pembaruan)

Mulai versi **2.0.x**, APK Indo HPL ditandatangani satu kunci tetap (sertifikat SHA-256 `97e5c5e2…c51861`). Karena itu pembaruan berikutnya bisa **dipasang menimpa** dan data tetap ada. Nomor versi naik otomatis (versionCode = 1000 + nomor run CI) dan terlihat di bagian bawah Pengaturan.

Kunci **tidak** disimpan di repo (repo ini publik). Owner menyimpan file kuncinya sendiri. Supaya CI bisa langsung menerbitkan APK siap pasang, isi dua secret di GitHub (Settings → Secrets and variables → Actions → New repository secret):

| Nama secret | Isi |
|---|---|
| `INDOHPL_KEYSTORE_B64` | isi file kunci dalam base64 (diberikan terpisah, jangan dibagikan) |
| `INDOHPL_KEYSTORE_PASSWORD` | kata sandi kunci |

Tanpa secret, CI hanya menyimpan bahan build tak-terpasang di branch `apk-indohpl-staging` (file `.bin`); bahan itu harus ditandatangani dulu dengan kunci permanen sebelum diberikan ke HP. CI menolak menerbitkan APK yang kuncinya bukan kunci permanen.

Versi lama (1.0.0 dan 1.x/1.2.x) memakai kunci acak per build, jadi Android **menolak** menimpanya dengan 2.0.x. Pindah sekali saja:

1. Di aplikasi lama: ekspor rekap setiap bulan yang ingin disimpan (Pengaturan → Ekspor → Bagikan file → simpan ke Drive/Files).
2. Uninstall aplikasi lama, pasang APK 2.0.x.
3. Pengaturan → buat PIN → **Impor data dari versi lama** → pilih file `presensi_YYYY-MM_detail.csv`. Absen, izin/sakit, telat, dan GPS masuk lagi. Foto selfie tidak ikut.
4. Atur ulang lokasi toko, hari libur, dan batas simpan data.

**Kalau file kunci hilang**, pembaruan tidak bisa lagi dipasang menimpa (harus uninstall + impor CSV lagi). Simpan dua salinan, misalnya di Google Drive pribadi dan di laptop.

Atau build sendiri di Android Studio (Ladybug atau lebih baru): buka folder proyek → *Build ▸ Build APK(s)*.
Repo ini sengaja tidak menyertakan `gradlew` (file biner wrapper). Kalau perlu, jalankan `gradle wrapper` sekali (Gradle 8.9 dipakai di CI).

## Yang perlu diketahui

* **Biometrik**: memakai sidik jari / wajah / PIN yang terdaftar di HP kasir. Android **tidak memberi tahu aplikasi sidik jari siapa** yang dipakai, jadi biometrik hanya gerbang "presensi dilakukan di HP kasir dengan persetujuan". Bukti identitas utamanya adalah **selfie**. Kalau ingin, daftarkan sidik jari tiap karyawan di HP kasir (Android biasanya mengizinkan sampai 5 jari).
* **Waktu** diambil dari jam HP kasir. Pastikan *Tanggal & waktu otomatis* aktif.
* **Data** (database + foto) tersimpan di folder privat aplikasi, dan yang lebih lama dari 2 bulan dihapus otomatis. Menghapus aplikasi = menghapus data. Ekspor rekap setiap akhir bulan.
* **GPS di dalam ruangan** bisa lambat/tidak akurat. Aplikasi menunggu sampai 25 detik dan memakai akurasi GPS sebagai toleransi. Kalau sering gagal, besarkan radius atau matikan "Wajib di lokasi toko".
* Butuh HP dengan **Google Play Services** (hampir semua HP Android di Indonesia).
* Minimal Android 8.0 (API 26).

## Struktur kode

```
app/src/main/java/com/indohpl/presensi/
├── PresensiApp.kt            Application: membuat Repository, seed karyawan
├── MainActivity.kt           FragmentActivity (dibutuhkan BiometricPrompt) + Compose
├── data/                     Room (Employee, AttendanceRecord, Holiday), DataStore settings, Repository
├── domain/                   Logika murni Kotlin: LateRule, GeoRule, Retention, CsvImport, BonusCalculator, RecapFormatter (diuji unit test)
├── ui/                       Compose: Beranda, Absen (biometrik+kamera), Rekap, Pengaturan
└── util/                     Biometric, LocationHelper (GPS), PhotoStamper, Export, CleanupScheduler (hapus data lama harian)
```

Unit test: `gradle testDebugUnitTest` (menguji perhitungan uang rajin dan aturan telat).
