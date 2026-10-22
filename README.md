# Presensi Karyawan — Indo HPL & EG Kost

Aplikasi Android untuk mencatat presensi (jam masuk) karyawan.
Setiap presensi diverifikasi dengan **biometrik HP** lalu **selfie berstempel waktu + koordinat GPS**, dan hanya sah bila HP berada di **lokasi kerja** (toko / kost).
Aplikasi menghitung **uang rajin** bulanan otomatis dan bisa mengekspor rekap untuk ditempel ke Claude.

Satu basis kode menghasilkan **dua aplikasi terpisah** (dua *product flavor* Gradle). Keduanya bisa dipasang berdampingan di satu HP karena package name-nya berbeda; data dan PIN owner-nya terpisah; di cloud datanya dipisah per tempat.

| | Presensi Indo HPL | Presensi EG Kost |
|---|---|---|
| Varian build | `indohpl` | `egkost` |
| Package name | `com.indohpl.presensi` | `com.egkost.presensi` |
| Dipakai di | HP kasir toko Indo HPL | HP presensi di EG Kost |
| Warna ikon | biru | hijau |
| Jam masuk bawaan | Perempuan 07:45 (+10 mnt) · Laki-laki 08:00 (+5 mnt) | **07:30** (+10 mnt) untuk semua |
| Unduh APK | [PresensiIndoHPL.apk](https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-build/PresensiIndoHPL.apk) | [PresensiEGKost.apk](https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-build/PresensiEGKost.apk) |

Konfigurasi tempat kerja ada di satu berkas per varian: `app/src/indohpl/java/com/indohpl/presensi/Site.kt` dan `app/src/egkost/java/com/indohpl/presensi/Site.kt` (nama, karyawan awal, jam masuk bawaan, teks "toko"/"kost"). Selain itu tiap varian punya `res/values/strings.xml` (nama aplikasi), `res/values/colors.xml` (warna ikon), dan `applicationId` di `app/build.gradle.kts`.

## Karyawan

Daftar ini di-seed otomatis setiap aplikasi dibuka (`Repository.ensureSeeded` membaca `Site.seedEmployees`; yang sudah ada dilewati).

**Indo HPL**

| Nama    | Gender |
|---------|--------|
| Sara    | P      |
| Riyanti | P      |
| Evan    | L      |
| Madi    | L      |
| Roni    | L      |

**EG Kost**

| Nama    | Bagian  | Gender |
|---------|---------|--------|
| Inul    | Laundry | P      |
| Bik Nia | Kos     | P      |

Bagian kerja ditampilkan di bawah nama di Beranda, dan sebagai "Nama · Bagian" di Rekap, stempel foto, serta tabel ringkasan Markdown. Menambah karyawan = menambah satu baris di `Site.kt` varian yang bersangkutan (pakai `id` baru yang belum terpakai), lalu push: APK baru otomatis dibangun, dan saat aplikasi dibuka karyawan baru langsung ditambahkan ke database HP yang sudah terpasang (karyawan yang sudah ada tidak diubah).

## Alur presensi

```
Beranda ──tap "Absen"──▶ [1] Biometrik HP presensi ──▶ [2] Selfie (kamera depan) + GPS
                                                        │
                                                        ▼
                  Cek jarak HP ke titik lokasi kerja (radius default 100 m, fake GPS ditolak)
                                                        │
                                                        ▼
   Foto distempel: nama (+ bagian) + tanggal/jam + koordinat + "Di lokasi EG Kost · 35 m" + "Presensi EG Kost"
                                                        │
                                                        ▼
                             Simpan ▶ status TEPAT / TELAT (menit) ▶ kembali ke Beranda
```

* Satu karyawan hanya bisa absen **sekali per hari** (dijaga di database).
* Status **telat** = jam foto lebih dari *jam masuk + toleransi*. Aturan bisa dibedakan per gender (diubah owner di Pengaturan):

  | Aplikasi | Siapa | Jam masuk | Toleransi | Batas dianggap tepat |
  |---|---|---|---|---|
  | Indo HPL | Perempuan (Sara, Riyanti) | 07:45 | 10 menit | 07:55:00 |
  | Indo HPL | Laki-laki (Evan, Madi, Roni) | 08:00 | 5 menit | 08:05:00 |
  | EG Kost | Semua (Inul, Bik Nia) | **07:30** | 10 menit | 07:40:00 |

  Menit telat dihitung dari jam masuk (di EG Kost datang 07:41 = telat 11 menit). Kalau aturan perempuan dan laki-laki sama, aplikasi menampilkannya sebagai satu aturan saja.
* Hanya **jam masuk** yang dicatat, tidak ada check-out.
* Langkah biometrik memakai kunci layar HP (sidik jari / wajah / PIN). Kalau HP presensi **tidak punya kunci layar**, aplikasi menampilkan pesan dengan tombol "Coba lagi"; owner bisa mematikan saklar **Pengaturan → Verifikasi sebelum selfie → Wajib biometrik** supaya absen langsung ke selfie.
* Pemegang HP bisa menandai **Izin** / **Sakit** dari menu ⋮ di kartu karyawan (tanpa selfie, tidak dipotong).
* Menu ⋮ juga bisa **Lihat foto** dan **Hapus catatan hari ini** (untuk salah tekan).

## Lokasi kerja (GPS)

1. Buka **Pengaturan → Lokasi toko Indo HPL** (atau **Lokasi EG Kost**), berdiri di dalam toko/kost, tekan **Pakai lokasi HP sekarang**, lalu **Simpan pengaturan**. Atau ketik koordinat dari Google Maps (tekan lama titik lokasi → salin angka `-6.xxxxxx, 106.xxxxxx`).
2. Saat absen, aplikasi mencari posisi GPS. Tombol jepret hanya aktif bila HP di dalam **radius** (default 100 m, akurasi GPS ikut diperhitungkan).
3. Koordinat, jarak ke titik lokasi, dan status "Di lokasi / DI LUAR LOKASI" dibakar ke foto dan disimpan di database + CSV.
4. Saklar **Wajib di lokasi** bisa dimatikan: absen tetap tercatat tapi ditandai "Luar lokasi" (merah) di Beranda, Rekap, dan ekspor.
5. Lokasi palsu (aplikasi fake GPS) terdeteksi dan ditolak.

## Aturan uang rajin (bisa diubah di Pengaturan)

| Parameter | Default |
|---|---|
| Uang rajin penuh per bulan | Rp 250.000 |
| Potongan per hari telat | otomatis = 250.000 ÷ jumlah hari kerja bulan itu (≈ Rp 8.333 untuk 30 hari kerja) |
| Hari kerja | Senin–Minggu (setiap hari), kecuali tanggal libur |
| Hari libur | daftar tanggal di Pengaturan (tidak dihitung hari kerja) |
| Alpa (tidak absen, tanpa izin/sakit) | ikut dipotong (bisa dimatikan) |

Rumus: `uang rajin = maks(0, 250.000 − potongan × (hari telat + hari alpa))`.
Izin/sakit **tidak** dipotong tetapi juga tidak dihitung "full bulan".

Kalau ingin potongan tetap (misal Rp 10.000 per telat), isi angka itu di *Potongan per hari telat*.

## Hak akses: pemegang HP vs owner

| Tab | Siapa | Isi |
|---|---|---|
| Beranda | pemegang HP (kasir / penjaga kost) | absen, tandai izin/sakit, lihat foto, hapus salah tekan |
| Rekap | pemegang HP & owner | **hanya melihat** rekap bulanan, tidak ada tombol ubah/ekspor |
| Pengaturan | **owner, dikunci PIN** | aturan jam, uang rajin, wajib biometrik, lokasi kerja, hari libur, ekspor rekap, ganti PIN |

PIN owner (4–8 angka) dibuat saat tab Pengaturan pertama kali dibuka. Disimpan sebagai hash (tidak bisa dibaca dari file). Salah 5 kali = terkunci 30 detik. Pindah tab = terkunci lagi. PIN yang lupa hanya bisa direset dengan menghapus data aplikasi.

## Dashboard owner + sinkron cloud (hybrid, gratis)

```
HP laundry / HP kasir                      Supabase (cloud, gratis)               Owner, dari mana saja
─────────────────────                      ────────────────────────               ─────────────────────
Aplikasi Android (absen)                   presensi TANPA foto                    Dashboard (PWA di browser)
 · selfie + GPS + tolak fake GPS   ──▶     (jam, status, lat/lon, jarak)          · presensi hari ini + status HP
 · tetap jalan tanpa internet     ◀──      pengaturan per tempat          ◀──▶    · rekap, uang rajin, ekspor
 · tarik pengaturan & karyawan             daftar karyawan                        · ubah aturan, libur, lokasi, karyawan
```

* **Foto selfie tidak pernah dikirim ke cloud.** Yang dikirim: karyawan, tanggal, jam masuk, status, menit telat, koordinat, jarak ke titik lokasi, di lokasi atau tidak.
* **Dashboard owner:** https://shinx2ran.github.io/Presensi-Indo-HPL/ — dibuka di browser HP/laptop, bisa dipasang ke layar utama (PWA). Masuk dengan kunci owner. Perubahan dashboard langsung berlaku tanpa memperbarui apa pun.
* **Menghubungkan HP:** dashboard → tab **HP** → *Hubungkan HP baru* → muncul kode 8 huruf (berlaku 15 menit). Di HP: **Pengaturan** (PIN owner) → **Sinkronisasi cloud** → isi kode → **Hubungkan**.
* **Pengaturan dari jauh:** jam masuk, toleransi, uang rajin, hari kerja, hari libur, lokasi + radius, wajib lokasi/biometrik, lama simpan foto, dan daftar karyawan bisa diubah di dashboard. HP menerapkannya saat sinkron (saat aplikasi dibuka, setelah absen, dan tiap jam). Perubahan dari Pengaturan di HP juga dikirim ke dashboard; yang terakhir disimpan yang berlaku.
* **Tanpa internet:** presensi tetap tercatat di HP dan dikirim otomatis saat internet kembali.
* Satu proyek Supabase (`presensi-karyawan`) untuk kedua tempat; data EG Kost dan Indo HPL dipisah. Detail teknis: [docs/CLOUD_API.md](docs/CLOUD_API.md).
* Paket gratis Supabase menidurkan proyek setelah 7 hari tanpa aktivitas. HP yang tersambung sinkron tiap jam, jadi proyek tetap aktif selama ada HP yang menyala dan online. Kalau tertidur, tidak ada data yang hilang: presensi menunggu di HP sampai proyek dibangunkan (satu klik di dashboard Supabase).

## Foto selfie dihapus otomatis

Foto selfie hanya disimpan di HP sebagai bukti, dan memakan memori. Aplikasi menghapus otomatis foto yang lebih tua dari **60 hari** (bisa diubah: Pengaturan di HP atau dashboard, 0 = simpan selamanya, selain itu 7–3650 hari). Data presensinya (jam, status, lokasi) tetap ada; hanya file fotonya yang dihapus, dan menu *Lihat foto* tidak muncul lagi untuk hari itu. Pembersihan berjalan sehari sekali dan saat aplikasi dibuka; di Pengaturan ada tombol **Hapus foto lama sekarang** dan info jumlah/ukuran foto.

## Rekap & ekspor ke Claude

Tab **Rekap** menampilkan per karyawan: hari kerja, hadir, tepat, telat, izin, sakit, alpa, total potongan, uang rajin.
Bulan berjalan ditandai "sementara". Ketuk nama untuk detail harian.

Ekspor ada di **Pengaturan (owner) → Ekspor rekap bulanan**. Tombol **Bagikan file** mengirim 3 file lewat menu bagikan Android (pilih aplikasi Claude, WhatsApp, Drive, dll):

| File | Isi |
|---|---|
| `presensi_YYYY-MM_detail.csv` | satu baris per presensi: tanggal, nama, status, jam masuk, menit telat, catatan, nama file foto, latitude, longitude, akurasi, jarak ke titik lokasi (`jarak_toko_m` / `jarak_kost_m`), di lokasi (YA/TIDAK) |
| `presensi_YYYY-MM_rekap.csv` | ringkasan per karyawan termasuk uang rajin |
| `presensi_YYYY-MM_rekap.md` | tabel Markdown lengkap, siap tempel ke Claude |

Tombol **Salin teks** menyalin rekap Markdown ke clipboard.

## Cara mendapatkan APK

Cara termudah: buka link ini di browser HP yang akan dipakai absen, file APK langsung terunduh:

| Aplikasi | Link unduh |
|---|---|
| Presensi Indo HPL (HP kasir) | **https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-build/PresensiIndoHPL.apk** |
| Presensi EG Kost | **https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-build/PresensiEGKost.apk** |

Link itu selalu berisi build terbaru. Setiap push ke GitHub menjalankan workflow **Build APK** (`.github/workflows/android.yml`) yang membangun kedua APK, menyimpannya ke branch `apk-build`, dan juga sebagai artifact di tab Actions.

1. Unduh APK lewat link di atas (atau tab **Actions** → run terbaru → **Artifacts**).
2. Ketuk file APK di HP, izinkan "instal dari sumber tidak dikenal", instal.
3. Buka aplikasi → beri izin kamera dan lokasi saat diminta. Nyalakan **Lokasi** di HP (mode akurasi tinggi).

Atau build sendiri di Android Studio (Ladybug atau lebih baru): buka folder proyek → pilih *Build Variant* `indohplDebug` atau `egkostDebug` → *Build ▸ Build APK(s)*.
Dari terminal: `gradle assembleIndohplDebug` atau `gradle assembleEgkostDebug`.
Repo ini sengaja tidak menyertakan `gradlew` (file biner wrapper). Kalau perlu, jalankan `gradle wrapper` sekali (Gradle 8.9 dipakai di CI).

## Pembaruan otomatis (OTA)

Setelah terpasang sekali, aplikasi memperbarui dirinya sendiri lewat internet, tanpa Play Store dan tanpa membuka link GitHub.

```
push ke GitHub ──▶ CI membangun APK bertanda tangan tetap ──▶ branch apk-build: APK + update-<varian>.json
                                                                          │
HP presensi membuka aplikasi ──▶ baca update-<varian>.json ──▶ versi lebih baru? ──▶ kartu "Versi baru tersedia"
                                                                          │
                    ketuk Perbarui ──▶ unduh + cek SHA-256, nama paket, nomor versi ──▶ ketuk Pasang ──▶ dialog Android "Perbarui"
```

* Diperiksa otomatis saat Beranda/Pengaturan dibuka (paling sering tiap 30 menit), atau manual di **Pengaturan → Pembaruan aplikasi → Cek pembaruan sekarang**.
* Data presensi, foto, PIN, dan pengaturan tetap ada setelah diperbarui.
* Pertama kali, Android meminta izin **"Izinkan dari sumber ini"** untuk aplikasi Presensi. Setelah itu cukup ketuk Pasang → Perbarui.
* Nomor versi naik otomatis tiap build (`versionCode = 100 + nomor run GitHub Actions`, `versionName = 1.2.<versionCode>`).
* Android tidak mengizinkan aplikasi biasa memasang pembaruan tanpa ketukan pengguna sama sekali; itu hanya bisa dengan Play Store atau MDM perusahaan.

**Syarat satu kali: kunci tanda tangan tetap.** Android hanya mau memasang pembaruan yang ditandatangani kunci yang sama. Owner menyimpan dua secret di GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

| Nama secret | Isi |
|---|---|
| `SIGNING_KEYSTORE_B64` | isi file kunci (base64) |
| `SIGNING_PASSWORD` | kata sandi kunci |

Tanpa secret, CI tetap membangun APK (kunci debug acak) tetapi tidak menerbitkan `update-*.json`, jadi aplikasi tidak menawarkan pembaruan. **Simpan cadangan kunci itu**: kalau hilang, semua HP harus uninstall-pasang ulang sekali lagi. Saat pertama beralih ke kunci tetap, HP yang terpasang versi lama (kunci acak) juga perlu uninstall-pasang ulang sekali; ekspor rekap dulu.

## Yang perlu diketahui

* **Biometrik**: memakai sidik jari / wajah / PIN yang terdaftar di HP presensi. Android **tidak memberi tahu aplikasi sidik jari siapa** yang dipakai, jadi biometrik hanya gerbang "presensi dilakukan di HP ini dengan persetujuan". Bukti identitas utamanya adalah **selfie**. Kalau ingin, daftarkan sidik jari tiap karyawan di HP itu (Android biasanya mengizinkan sampai 5 jari).
* **Layar Absen hanya menampilkan "Memuat…"** (versi ≤ 1.1.0): HP belum punya kunci layar sehingga biometrik tidak tersedia, dan pesannya tidak tampil. Sejak 1.1.1 pesannya terlihat; solusinya pasang kunci layar di HP, atau matikan **Wajib biometrik** di Pengaturan (owner).
* **Waktu** diambil dari jam HP presensi. Pastikan *Tanggal & waktu otomatis* aktif.
* **Data** (database + foto) tersimpan di folder privat aplikasi. Menghapus aplikasi = menghapus data. Ekspor rekap setiap akhir bulan.
* **GPS di dalam ruangan** bisa lambat/tidak akurat, terutama di HP kelas murah. Aplikasi langsung memakai posisi pertama yang didapat (fix pertama dari jaringan/GPS, atau posisi terakhir HP yang umurnya < 5 menit) sehingga tombol jepret cepat aktif, lalu terus memperbaiki akurasinya di latar sampai ≤ 30 m atau maksimal 40 detik; akurasi dipakai sebagai toleransi jarak. Kalau saklar Lokasi HP mati, aplikasi langsung memberi tahu tanpa menunggu. Kalau tetap gagal, nyalakan Wi-Fi (membantu penentuan posisi walau tidak tersambung), besarkan radius, atau matikan saklar "Wajib di lokasi toko" / "Wajib di lokasi kost".
* Butuh HP dengan **Google Play Services** (hampir semua HP Android di Indonesia).
* Minimal Android 8.0 (API 26).

## Struktur kode

```
app/src/main/java/com/indohpl/presensi/
├── SiteConfig.kt             Bentuk konfigurasi tempat kerja (nama, karyawan awal, jam masuk, teks "toko"/"kost")
├── PresensiApp.kt            Application: membuat Repository, seed karyawan dari Site
├── MainActivity.kt           FragmentActivity (dibutuhkan BiometricPrompt) + Compose
├── data/                     Room (Employee + bagian kerja, AttendanceRecord, Holiday), DataStore settings, Repository
├── cloud/                    CloudEndpoint, SupabaseRpc (HTTP), CloudMapper (JSON), CloudSync (WorkManager: kirim, tarik pengaturan)
├── domain/                   Logika murni Kotlin: LateRule, GeoRule (jarak/radius), BonusCalculator, RecapFormatter (diuji unit test)
├── ui/                       Compose: Beranda, Absen (biometrik+kamera), Rekap, Pengaturan
└── util/                     Biometric, LocationHelper (GPS), PhotoStamper (stempel foto), Export (share/clipboard)
app/src/indohpl/              Varian Indo HPL: Site.kt (konfigurasi), strings.xml (nama aplikasi), colors.xml (warna ikon)
app/src/egkost/               Varian EG Kost: Site.kt, strings.xml, colors.xml
app/src/test/                 Unit test bersama; app/src/testIndohpl & testEgkost memeriksa konfigurasi tiap varian
dashboard/                    PWA dashboard owner (HTML/JS tanpa build), diterbitkan ke branch gh-pages; tests/ = node --test
supabase/migrations/          Skema database cloud (tabel + RPC berkunci)
```

### Menambah tempat kerja baru

1. Tambah flavor baru di `app/build.gradle.kts` (dimensi `site`, `applicationId` unik).
2. Buat `app/src/<varian>/java/com/indohpl/presensi/Site.kt` berisi `val Site = SiteConfig(...)`, plus `res/values/strings.xml` (`app_name`) dan `colors.xml` (`ic_launcher_background`).
3. Tambahkan task `assemble<Varian>Debug` dan `test<Varian>DebugUnitTest` di workflow CI.

Unit test: `gradle testIndohplDebugUnitTest testEgkostDebugUnitTest` (menguji perhitungan uang rajin, aturan telat, dan konfigurasi tiap varian).
