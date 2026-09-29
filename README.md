# Presensi Indo HPL

Aplikasi Android untuk mencatat presensi (jam masuk) karyawan di **HP kasir**.
Setiap presensi diverifikasi dengan **biometrik HP** lalu **selfie berstempel waktu**.
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
Beranda ──tap "Absen"──▶ [1] Biometrik HP kasir ──▶ [2] Selfie (kamera depan)
                                                        │
                                                        ▼
                             Foto distempel: nama + tanggal/jam + "Presensi Indo HPL"
                                                        │
                                                        ▼
                             Simpan ▶ status TEPAT / TELAT (menit) ▶ kembali ke Beranda
```

* Satu karyawan hanya bisa absen **sekali per hari** (dijaga di database).
* Status **telat** = jam foto lebih dari *jam masuk + toleransi* (default 08:00, toleransi 0).
* Kasir bisa menandai **Izin** / **Sakit** dari menu ⋮ di kartu karyawan (tanpa selfie, tidak dipotong).
* Menu ⋮ juga bisa **Lihat foto** dan **Hapus catatan hari ini** (untuk salah tekan).

## Aturan uang rajin (bisa diubah di Pengaturan)

| Parameter | Default |
|---|---|
| Uang rajin penuh per bulan | Rp 250.000 |
| Potongan per hari telat | otomatis = 250.000 ÷ jumlah hari kerja bulan itu (≈ Rp 9.615 untuk 26 hari kerja) |
| Hari kerja | Senin–Sabtu |
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
| `presensi_YYYY-MM_detail.csv` | satu baris per presensi: tanggal, nama, status, jam masuk, menit telat, catatan, nama file foto |
| `presensi_YYYY-MM_rekap.csv` | ringkasan per karyawan termasuk uang rajin |
| `presensi_YYYY-MM_rekap.md` | tabel Markdown lengkap, siap tempel ke Claude |

Tombol **Salin teks** menyalin rekap Markdown ke clipboard.

## Cara mendapatkan APK

Setiap push ke GitHub menjalankan workflow **Build APK** (`.github/workflows/android.yml`):

1. Buka tab **Actions** di repo → pilih run terbaru → bagian **Artifacts** → unduh `presensi-indo-hpl-debug`.
2. Ekstrak `app-debug.apk`, kirim ke HP kasir, izinkan "instal dari sumber tidak dikenal", instal.
3. Buka aplikasi → beri izin kamera saat diminta.

Atau build sendiri di Android Studio (Ladybug atau lebih baru): buka folder proyek → *Build ▸ Build APK(s)*.
Repo ini sengaja tidak menyertakan `gradlew` (file biner wrapper). Kalau perlu, jalankan `gradle wrapper` sekali (Gradle 8.9 dipakai di CI).

## Yang perlu diketahui

* **Biometrik**: memakai sidik jari / wajah / PIN yang terdaftar di HP kasir. Android **tidak memberi tahu aplikasi sidik jari siapa** yang dipakai, jadi biometrik hanya gerbang "presensi dilakukan di HP kasir dengan persetujuan". Bukti identitas utamanya adalah **selfie**. Kalau ingin, daftarkan sidik jari tiap karyawan di HP kasir (Android biasanya mengizinkan sampai 5 jari).
* **Waktu** diambil dari jam HP kasir. Pastikan *Tanggal & waktu otomatis* aktif.
* **Data** (database + foto) tersimpan di folder privat aplikasi. Menghapus aplikasi = menghapus data. Ekspor rekap setiap akhir bulan.
* Minimal Android 8.0 (API 26).

## Struktur kode

```
app/src/main/java/com/indohpl/presensi/
├── PresensiApp.kt            Application: membuat Repository, seed karyawan
├── MainActivity.kt           FragmentActivity (dibutuhkan BiometricPrompt) + Compose
├── data/                     Room (Employee, AttendanceRecord, Holiday), DataStore settings, Repository
├── domain/                   Logika murni Kotlin: LateRule, BonusCalculator, RecapFormatter (diuji unit test)
├── ui/                       Compose: Beranda, Absen (biometrik+kamera), Rekap, Pengaturan
└── util/                     Biometric, PhotoStamper (stempel foto), Export (share/clipboard)
```

Unit test: `gradle testDebugUnitTest` (menguji perhitungan uang rajin dan aturan telat).
