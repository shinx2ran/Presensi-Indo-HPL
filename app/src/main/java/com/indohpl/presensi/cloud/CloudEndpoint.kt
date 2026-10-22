package com.indohpl.presensi.cloud

/** Alamat cloud (Supabase, proyek presensi-karyawan) dan dashboard owner. Kontrak lengkap: docs/CLOUD_API.md. */
object CloudEndpoint {
    const val SUPABASE_URL = "https://yqgcovruqtfoamjynuyf.supabase.co"

    /**
     * Kunci publik (publishable) Supabase. Memang boleh ada di dalam aplikasi/halaman web: kunci ini hanya
     * mengizinkan memanggil RPC yang disediakan, dan setiap RPC memeriksa kunci HP / kunci owner sendiri.
     * Tabel tidak bisa dibaca langsung.
     */
    const val PUBLISHABLE_KEY = "sb_publishable_jhA_daI9x0x0q4Bi3-uesQ_i0qlQm3f"

    /** Dashboard owner (PWA di GitHub Pages): tempat membuat kode untuk menghubungkan HP. */
    const val DASHBOARD_URL = "https://shinx2ran.github.io/Presensi-Indo-HPL/"
}
