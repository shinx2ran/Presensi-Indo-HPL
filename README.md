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

## Aturan uang rajin (bisa diubah di Pengaturan)

| Parameter | Default |
|---|---|
| Uang rajin penuh per bulan | Rp 250.000 |
| Potongan per hari telat | otomatis = 250.000 ÷ jumlah hari kerja bulan itu (≈ Rp 8.333 untuk 30 hari kerja) |
| Hari kerja | Senin–Minggu (setiap hari), kecuali tanggal libur toko |
| Hari libur toko | daftar tanggal di Pengaturan (tidak dihitung hari kerja) |
| Alpa (tidak absen, tanpa izin/sakit) | ikut dipotong (bisa dimatikan) |

Rumus: `uang rajin = maks(0, 250.000 − potongan × (hari telat + hari alpa))`.
Izin/sakit **tidak** dipotong tetapi juga tidak dihitung "full bulan".

Kalau ingin potongan tetap (misal Rp 10.000 per telat), isi angka itu di *Potongan per hari telat*.

## Rekap & ekspor ke Claude

Tab **Rekap** menampilkan per karyawan: hari kerja, hadir, tepat, telat, izin, sakit, alpa, total potongan, uang rajin.
Bulan berjalan ditandai "sementara".

Tombol **Bagikan file** mengirim 3 file lewat menu bagikan Android (pilih aplikasi Claude, WhatsApp, Drive, dll):

| File | Isi |
|---|---|
| `presensi_YYYY-MM_detail.csv` | satu baris per presensi: tanggal, nama, status, jam masuk, menit telat, catatan, nama file foto, latitude, longitude, akurasi, jarak ke toko, di lokasi (YA/TIDAK) |
| `presensi_YYYY-MM_rekap.csv` | ringkasan per karyawan termasuk uang rajin |
| `presensi_YYYY-MM_rekap.md` | tabel Markdown lengkap, siap tempel ke Claude |

Tombol **Salin teks** menyalin rekap Markdown ke clipboard.

## Cara mendapatkan APK

Cara termudah: buka link ini di browser HP kasir, file APK langsung terunduh:

**https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-build/PresensiIndoHPL.apk**

Link itu selalu berisi build terbaru. Setiap push ke GitHub menjalankan workflow **Build APK** (`.github/workflows/android.yml`) yang membangun APK, menyimpannya ke branch `apk-build`, dan juga sebagai artifact di tab Actions.

1. Unduh APK lewat link di atas (atau tab **Actions** → run terbaru → **Artifacts**).
2. Ketuk file APK di HP kasir, izinkan "instal dari sumber tidak dikenal", instal.
3. Buka aplikasi → beri izin kamera dan lokasi saat diminta. Nyalakan **Lokasi** di HP (mode akurasi tinggi).

Atau build sendiri di Android Studio (Ladybug atau lebih baru): buka folder proyek → *Build ▸ Build APK(s)*.
Repo ini sengaja tidak menyertakan `gradlew` (file biner wrapper). Kalau perlu, jalankan `gradle wrapper` sekali (Gradle 8.9 dipakai di CI).

## Yang perlu diketahui

* **Biometrik**: memakai sidik jari / wajah / PIN yang terdaftar di HP kasir. Android **tidak memberi tahu aplikasi sidik jari siapa** yang dipakai, jadi biometrik hanya gerbang "presensi dilakukan di HP kasir dengan persetujuan". Bukti identitas utamanya adalah **selfie**. Kalau ingin, daftarkan sidik jari tiap karyawan di HP kasir (Android biasanya mengizinkan sampai 5 jari).
* **Waktu** diambil dari jam HP kasir. Pastikan *Tanggal & waktu otomatis* aktif.
* **Data** (database + foto) tersimpan di folder privat aplikasi. Menghapus aplikasi = menghapus data. Ekspor rekap setiap akhir bulan.
* **GPS di dalam ruangan** bisa lambat/tidak akurat. Aplikasi menunggu sampai 25 detik dan memakai akurasi GPS sebagai toleransi. Kalau sering gagal, besarkan radius atau matikan "Wajib di lokasi toko".
* Butuh HP dengan **Google Play Services** (hampir semua HP Android di Indonesia).
* Minimal Android 8.0 (API 26).

## Struktur kode

```
app/src/main/java/com/indohpl/presensi/
├── PresensiApp.kt            Application: membuat Repository, seed karyawan
├── MainActivity.kt           FragmentActivity (dibutuhkan BiometricPrompt) + Compose
├── data/                     Room (Employee, AttendanceRecord, Holiday), DataStore settings, Repository
├── domain/                   Logika murni Kotlin: LateRule, GeoRule (jarak/radius), BonusCalculator, RecapFormatter (diuji unit test)
├── ui/                       Compose: Beranda, Absen (biometrik+kamera), Rekap, Pengaturan
└── util/                     Biometric, LocationHelper (GPS), PhotoStamper (stempel foto), Export (share/clipboard)
```

Unit test: `gradle testDebugUnitTest` (menguji perhitungan uang rajin dan aturan telat).
