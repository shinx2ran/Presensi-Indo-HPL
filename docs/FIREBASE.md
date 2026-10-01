# Panduan Firebase (sinkronisasi cloud, gratis)

Tujuan: setiap presensi di HP kasir otomatis terkirim ke **Firestore** (database Google) milik Anda,
sehingga bisa dilihat dari HP owner dan aman kalau HP kasir hilang.
Semua yang dipakai ada di paket **Spark (gratis, tanpa kartu kredit)**: Firestore + Authentication.
Foto disimpan di dalam Firestore (dikompres sekitar 100 KB), **bukan** di Cloud Storage (yang sejak 2026 butuh kartu kredit).

Perkiraan pemakaian: 5 karyawan × 30 hari × 100 KB ≈ 15 MB per bulan. Kuota gratis 1 GiB cukup sekitar 5 tahun.

## Bagian 1. Buat proyek Firebase (sekali, sekitar 15 menit, dari komputer lebih mudah)

1. Buka https://console.firebase.google.com dan login dengan akun Google Anda.
2. Klik **Create a project** (Buat proyek). Nama: `presensi-indo-hpl`. Google Analytics boleh **dimatikan**. Klik Create.
3. Di menu kiri **Build → Firestore Database → Create database**.
   - Location: pilih **asia-southeast2 (Jakarta)**.
   - Pilih **Start in production mode**. Klik Create.
4. Masih di Firestore, buka tab **Rules**, ganti seluruh isinya dengan ini lalu klik **Publish**:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /{document=**} {
         allow read, write: if request.auth != null;
       }
     }
   }
   ```

   Artinya: hanya yang sudah login (akun di langkah 5) yang boleh membaca/menulis.
5. Menu kiri **Build → Authentication → Get started**.
   - Tab **Sign-in method** → pilih **Email/Password** → Enable → Save.
   - Tab **Users** → **Add user** → isi email (misal `kasir@indohpl.id`, tidak harus email sungguhan) dan password yang kuat. Catat keduanya.
6. Klik ikon roda gigi di kiri atas → **Project settings**. Gulir ke bawah ke **Your apps** → klik ikon **Android**.
   - Android package name: `com.indohpl.presensi`
   - Nickname: `Presensi Indo HPL`
   - SHA-1: kosongkan. Klik **Register app**.
   - Klik **Download google-services.json**. Buka file itu dengan Notepad. Anda butuh 3 nilai:

   | Nilai di file | Kolom di aplikasi |
   |---|---|
   | `"project_id": "presensi-indo-hpl"` | Project ID |
   | `"mobilesdk_app_id": "1:1234567890:android:abc..."` | App ID |
   | `"current_key": "AIza..."` | API key |

   Klik Next sampai selesai (langkah "Add Firebase SDK" boleh dilewati, aplikasi tidak memakai file itu).

## Bagian 2. Isi di aplikasi (HP kasir)

1. Buka aplikasi → tab **Pengaturan** → masukkan PIN owner.
2. Bagian **Sinkronisasi cloud (Firebase)**: isi Project ID, App ID, API key, email, password dari Bagian 1.
3. Ketuk **Hubungkan & uji**. Kalau berhasil muncul "Terhubung ke proyek … sebagai …" dan saklar otomatis aktif.
4. Ketuk **Kirim ulang semua** supaya presensi yang sudah ada ikut terkirim.
5. Baris status di bawah saklar menunjukkan "Semua tersinkron" atau jumlah yang masih menunggu internet.

## Bagian 3. Lihat dari HP owner

1. Pasang APK yang sama di HP Anda (link di README).
2. Pengaturan → buat PIN owner → bagian Firebase: isi data yang sama → **Hubungkan & uji**.
3. Tab **Rekap** sekarang punya pilihan **HP ini / Cloud**. Pilih **Cloud**: data dari HP kasir muncul, ketuk nama untuk detail dan foto.
4. **Pengaturan → Ekspor rekap bulanan** juga punya pilihan Cloud, jadi file untuk Claude bisa dibuat dari HP Anda.

Jangan absen dari HP owner: HP owner hanya untuk melihat. (Kalau terlanjur, hapus lewat menu titik tiga.)

## Kalau "Hubungkan & uji" gagal

| Pesan | Penyebab | Solusi |
|---|---|---|
| `API key not valid` / `requests from this Android client application are blocked` | API key dibatasi ke sidik jari aplikasi (SHA-1) | Buka https://console.cloud.google.com/apis/credentials, pilih proyek yang sama, klik key "Android key (auto created by Firebase)", pada **Application restrictions** pilih **None**, Save. Tunggu 1–2 menit. |
| `INVALID_LOGIN_CREDENTIALS` / `password is invalid` | Email/password tidak cocok dengan user di Authentication | Cek tab Users di Firebase, atau reset password di sana |
| `PERMISSION_DENIED` | Rules Firestore belum dipublish | Ulangi Bagian 1 langkah 4 |
| `CONFIGURATION_NOT_FOUND` | Email/Password belum di-enable | Ulangi Bagian 1 langkah 5 |
| Tidak ada respons | Tidak ada internet | Cek koneksi HP |

## Struktur data di Firestore (untuk yang ingin tahu)

Koleksi `attendance`, satu dokumen per karyawan per tanggal, ID `YYYY-MM-DD_<idKaryawan>`:

```
employeeId, employeeName, date, timestamp, timeIn, status (TEPAT/TELAT/IZIN/SAKIT), lateMinutes,
note, latitude, longitude, accuracy, distanceMeters, inLocation, locationNote,
photoBase64 (foto JPEG kecil), device, uploadedAt
```

Menghapus presensi di HP kasir juga menghapus dokumennya di cloud.
