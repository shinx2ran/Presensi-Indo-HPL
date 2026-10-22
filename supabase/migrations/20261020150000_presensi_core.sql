-- Presensi karyawan (EG Kost, Indo HPL): data presensi TANPA foto, pengaturan jarak jauh, daftar karyawan, kunci HP.
-- Semua akses lewat fungsi RPC SECURITY DEFINER yang memeriksa kunci (owner / HP). Tabel tidak bisa dibaca langsung.
-- Proyek Supabase: presensi-karyawan (yqgcovruqtfoamjynuyf). Diterapkan lewat Supabase MCP (dipecah jadi
-- migrasi presensi_00..12 karena batas waktu alat; isi berkas ini = keadaan akhir database).
-- Tidak ada DELETE: HP yang diputus dan presensi "salah tekan" hanya ditandai (revoked_at / deleted_at).

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table public.sites (
  id text primary key,
  name text not null,
  default_settings jsonb not null default '{}'::jsonb
);

create table public.api_keys (
  id uuid primary key default gen_random_uuid(),
  key_hash text not null unique,
  role text not null check (role in ('owner', 'device')),
  site_id text references public.sites(id) on delete cascade,
  label text not null default '',
  app_version text,
  unsynced int,
  settings_version bigint,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz,
  revoked_at timestamptz,             -- HP diputus owner (hapus lunak)
  check ((role = 'owner') = (site_id is null))
);

create table public.pairing_codes (
  code_hash text primary key,
  site_id text not null references public.sites(id) on delete cascade,
  label text not null default '',
  expires_at timestamptz not null,
  used_at timestamptz
);

create table public.site_settings (
  site_id text primary key references public.sites(id) on delete cascade,
  version bigint not null check (version > 0),
  data jsonb not null,
  updated_at timestamptz not null default now(),
  updated_by text not null
);

create table public.employees (
  site_id text not null references public.sites(id) on delete cascade,
  id bigint not null check (id > 0),
  name text not null check (length(btrim(name)) between 1 and 60),
  gender text not null check (gender in ('P', 'L')),
  role text not null default '' check (length(role) <= 40),
  active boolean not null default true,
  updated_at timestamptz not null default now(),
  primary key (site_id, id)
);

create table public.attendance (
  site_id text not null references public.sites(id) on delete cascade,
  employee_id bigint not null,
  date date not null,
  time_in text not null default '',
  ts timestamptz,
  status text not null check (status in ('TEPAT', 'TELAT', 'IZIN', 'SAKIT')),
  late_minutes int not null default 0,
  note text not null default '',
  latitude double precision,
  longitude double precision,
  accuracy real,
  distance_m int,
  in_location boolean not null default false,
  location_note text not null default '',
  device text not null default '',
  uploaded_at timestamptz not null default now(),
  deleted_at timestamptz,             -- presensi dihapus di HP (salah tekan), hapus lunak
  primary key (site_id, employee_id, date)
);
create index attendance_site_date_idx on public.attendance (site_id, date);
create index api_keys_site_idx on public.api_keys (site_id);
create index pairing_codes_site_idx on public.pairing_codes (site_id);

alter table public.sites enable row level security;
alter table public.api_keys enable row level security;
alter table public.pairing_codes enable row level security;
alter table public.site_settings enable row level security;
alter table public.employees enable row level security;
alter table public.attendance enable row level security;
revoke all on public.sites, public.api_keys, public.pairing_codes, public.site_settings, public.employees, public.attendance
  from anon, authenticated;

insert into public.sites (id, name, default_settings) values
  ('egkost', 'EG Kost', '{"workStartFemale":"07:30","toleranceFemale":10,"workStartMale":"07:30","toleranceMale":10,"monthlyBonus":250000,"lateDeduction":0,"deductAbsent":true,"workDays":[1,2,3,4,5,6,7],"storeLat":null,"storeLon":null,"radiusMeters":100,"requireLocation":true,"requireBiometric":true,"photoRetentionDays":60,"holidays":[]}'),
  ('indohpl', 'Indo HPL', '{"workStartFemale":"07:45","toleranceFemale":10,"workStartMale":"08:00","toleranceMale":5,"monthlyBonus":250000,"lateDeduction":0,"deductAbsent":true,"workDays":[1,2,3,4,5,6,7],"storeLat":null,"storeLon":null,"radiusMeters":100,"requireLocation":true,"requireBiometric":true,"photoRetentionDays":60,"holidays":[]}');

-- ---------- helper privat ----------

create function private.hash(p text) returns text
language sql immutable set search_path = '' as $$
  select encode(sha256(convert_to(coalesce(p, ''), 'UTF8')), 'hex')
$$;

create function private.owner_check(p_key text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare v uuid;
begin
  if p_key is not null and length(p_key) >= 32 then
    update public.api_keys set last_seen_at = now()
      where key_hash = private.hash(p_key) and role = 'owner'
      returning id into v;
  end if;
  if v is null then
    raise exception 'Kunci owner tidak valid.' using errcode = '28000';
  end if;
  return v;
end $$;

create function private.device_site(p_key text) returns public.api_keys
language plpgsql security definer set search_path = '' as $$
declare r public.api_keys;
begin
  if p_key is not null and length(p_key) >= 32 then
    select * into r from public.api_keys
      where key_hash = private.hash(p_key) and role = 'device' and revoked_at is null;
  end if;
  if r.id is null then
    raise exception 'HP ini belum/tidak lagi terhubung. Hubungkan ulang dengan kode dari dashboard owner.' using errcode = '28000';
  end if;
  return r;
end $$;

create function private.site_exists(p_site text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not exists (select 1 from public.sites where id = p_site) then
    raise exception 'Tempat kerja tidak dikenal: %', p_site using errcode = '22023';
  end if;
end $$;

-- Validasi pengaturan (bentuk JSON sama dengan yang dipakai aplikasi Android dan dashboard).
create function private.validate_settings(d jsonb) returns void
language plpgsql immutable set search_path = '' as $$
declare k text; h jsonb;
begin
  if d is null or jsonb_typeof(d) <> 'object' then raise exception 'Pengaturan harus berupa objek JSON.' using errcode = '22023'; end if;
  if length(d::text) > 30000 then raise exception 'Pengaturan terlalu besar.' using errcode = '22023'; end if;
  foreach k in array array['workStartFemale', 'workStartMale'] loop
    if d ? k and (jsonb_typeof(d->k) <> 'string' or (d->>k) !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$') then
      raise exception 'Jam masuk % harus HH:mm.', k using errcode = '22023';
    end if;
  end loop;
  foreach k in array array['toleranceFemale', 'toleranceMale'] loop
    if d ? k and (jsonb_typeof(d->k) <> 'number' or (d->>k)::numeric not between 0 and 240) then
      raise exception 'Toleransi % harus 0-240 menit.', k using errcode = '22023';
    end if;
  end loop;
  if d ? 'monthlyBonus' and (jsonb_typeof(d->'monthlyBonus') <> 'number' or (d->>'monthlyBonus')::numeric not between 0 and 100000000) then
    raise exception 'Uang rajin tidak valid.' using errcode = '22023'; end if;
  if d ? 'lateDeduction' and (jsonb_typeof(d->'lateDeduction') <> 'number' or (d->>'lateDeduction')::numeric not between 0 and 100000000) then
    raise exception 'Potongan tidak valid.' using errcode = '22023'; end if;
  if d ? 'workDays' and (jsonb_typeof(d->'workDays') <> 'array' or jsonb_array_length(d->'workDays') not between 1 and 7
      or exists (select 1 from jsonb_array_elements(d->'workDays') e where jsonb_typeof(e) <> 'number' or e::text::numeric not in (1,2,3,4,5,6,7))) then
    raise exception 'Hari kerja harus daftar angka 1 (Senin) sampai 7 (Minggu).' using errcode = '22023'; end if;
  if d ? 'radiusMeters' and (jsonb_typeof(d->'radiusMeters') <> 'number' or (d->>'radiusMeters')::numeric not between 10 and 100000) then
    raise exception 'Radius minimal 10 meter.' using errcode = '22023'; end if;
  if d ? 'photoRetentionDays' and (jsonb_typeof(d->'photoRetentionDays') <> 'number'
      or not ((d->>'photoRetentionDays')::numeric = 0 or (d->>'photoRetentionDays')::numeric between 7 and 3650)) then
    raise exception 'Simpan foto: 0 (selamanya) atau 7-3650 hari.' using errcode = '22023'; end if;
  if d ? 'storeLat' and jsonb_typeof(d->'storeLat') <> 'null' and (jsonb_typeof(d->'storeLat') <> 'number' or (d->>'storeLat')::numeric not between -90 and 90) then
    raise exception 'Lintang tidak valid.' using errcode = '22023'; end if;
  if d ? 'storeLon' and jsonb_typeof(d->'storeLon') <> 'null' and (jsonb_typeof(d->'storeLon') <> 'number' or (d->>'storeLon')::numeric not between -180 and 180) then
    raise exception 'Bujur tidak valid.' using errcode = '22023'; end if;
  foreach k in array array['deductAbsent', 'requireLocation', 'requireBiometric'] loop
    if d ? k and jsonb_typeof(d->k) <> 'boolean' then raise exception '% harus true/false.', k using errcode = '22023'; end if;
  end loop;
  if d ? 'holidays' then
    if jsonb_typeof(d->'holidays') <> 'array' or jsonb_array_length(d->'holidays') > 400 then
      raise exception 'Daftar hari libur tidak valid.' using errcode = '22023'; end if;
    for h in select * from jsonb_array_elements(d->'holidays') loop
      if jsonb_typeof(h) <> 'object' or coalesce(h->>'date', '') !~ '^\d{4}-\d{2}-\d{2}$' or length(coalesce(h->>'name', '')) > 60 then
        raise exception 'Hari libur harus {date: yyyy-mm-dd, name}.' using errcode = '22023'; end if;
    end loop;
  end if;
end $$;

create function private.site_payload(p_site text) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'site', (select jsonb_build_object('id', s.id, 'name', s.name, 'defaultSettings', s.default_settings) from public.sites s where s.id = p_site),
    'settings', (select jsonb_build_object('version', ss.version, 'data', ss.data, 'updatedAt', ss.updated_at, 'updatedBy', ss.updated_by)
                 from public.site_settings ss where ss.site_id = p_site),
    'employees', coalesce((select jsonb_agg(jsonb_build_object('id', e.id, 'name', e.name, 'gender', e.gender, 'role', e.role, 'active', e.active) order by e.id)
                 from public.employees e where e.site_id = p_site), '[]'::jsonb)
  )
$$;

-- ---------- RPC owner (dashboard) ----------

create function public.owner_sites(p_key text) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform private.owner_check(p_key);
  return coalesce((select jsonb_agg(jsonb_build_object('id', id, 'name', name) order by name) from public.sites), '[]'::jsonb);
end $$;

create function public.owner_overview(p_key text, p_site text, p_from date, p_to date) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
  perform private.owner_check(p_key);
  perform private.site_exists(p_site);
  if p_from is null or p_to is null or p_to < p_from or p_to - p_from > 400 then
    raise exception 'Rentang tanggal tidak valid (maks 400 hari).' using errcode = '22023';
  end if;
  return private.site_payload(p_site) || jsonb_build_object(
    'attendance', coalesce((select jsonb_agg(jsonb_build_object(
        'employeeId', a.employee_id, 'date', a.date, 'timeIn', a.time_in,
        'timestamp', (extract(epoch from a.ts) * 1000)::bigint,
        'status', a.status, 'lateMinutes', a.late_minutes, 'note', a.note,
        'latitude', a.latitude, 'longitude', a.longitude, 'accuracy', a.accuracy,
        'distanceMeters', a.distance_m, 'inLocation', a.in_location, 'locationNote', a.location_note,
        'device', a.device, 'uploadedAt', a.uploaded_at) order by a.date, a.ts)
      from public.attendance a
      where a.site_id = p_site and a.date between p_from and p_to and a.deleted_at is null), '[]'::jsonb),
    'devices', coalesce((select jsonb_agg(jsonb_build_object(
        'id', k.id, 'label', k.label, 'appVersion', k.app_version, 'unsynced', k.unsynced,
        'settingsVersion', k.settings_version, 'lastSeenAt', k.last_seen_at, 'createdAt', k.created_at) order by k.created_at)
      from public.api_keys k where k.role = 'device' and k.site_id = p_site and k.revoked_at is null), '[]'::jsonb),
    'serverTime', now()
  );
end $$;

create function public.owner_save_settings(p_key text, p_site text, p_data jsonb, p_base_version bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare cur bigint;
begin
  perform private.owner_check(p_key);
  perform private.site_exists(p_site);
  perform private.validate_settings(p_data);
  select version into cur from public.site_settings where site_id = p_site for update;
  if cur is null then
    if coalesce(p_base_version, 0) <> 0 then return jsonb_build_object('ok', false, 'version', 0); end if;
    insert into public.site_settings (site_id, version, data, updated_by) values (p_site, 1, p_data, 'owner')
      on conflict (site_id) do nothing;
    if not found then return jsonb_build_object('ok', false, 'version', (select version from public.site_settings where site_id = p_site)); end if;
    return jsonb_build_object('ok', true, 'version', 1);
  end if;
  if p_base_version is not null and p_base_version <> cur then
    return jsonb_build_object('ok', false, 'version', cur);
  end if;
  update public.site_settings set version = cur + 1, data = p_data, updated_at = now(), updated_by = 'owner' where site_id = p_site;
  return jsonb_build_object('ok', true, 'version', cur + 1);
end $$;

create function public.owner_save_employee(p_key text, p_site text, p_employee jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_id bigint; v_name text; v_gender text; v_role text; v_active boolean;
begin
  perform private.owner_check(p_key);
  perform private.site_exists(p_site);
  if p_employee is null or jsonb_typeof(p_employee) <> 'object' then raise exception 'Data karyawan tidak valid.' using errcode = '22023'; end if;
  v_name := btrim(coalesce(p_employee->>'name', ''));
  v_gender := coalesce(p_employee->>'gender', 'P');
  v_role := btrim(coalesce(p_employee->>'role', ''));
  v_active := coalesce((p_employee->>'active')::boolean, true);
  if length(v_name) not between 1 and 60 then raise exception 'Nama wajib diisi (maks 60 huruf).' using errcode = '22023'; end if;
  if v_gender not in ('P', 'L') then raise exception 'Gender harus P atau L.' using errcode = '22023'; end if;
  if length(v_role) > 40 then raise exception 'Bagian maks 40 huruf.' using errcode = '22023'; end if;
  v_id := nullif(p_employee->>'id', '')::bigint;
  if v_id is null then
    perform pg_advisory_xact_lock(hashtext('employees:' || p_site));
    select coalesce(max(id), 0) + 1 into v_id from public.employees where site_id = p_site;
  elsif v_id <= 0 then
    raise exception 'ID karyawan tidak valid.' using errcode = '22023';
  end if;
  insert into public.employees (site_id, id, name, gender, role, active, updated_at)
    values (p_site, v_id, v_name, v_gender, v_role, v_active, now())
    on conflict (site_id, id) do update set name = excluded.name, gender = excluded.gender, role = excluded.role,
      active = excluded.active, updated_at = now();
  return jsonb_build_object('id', v_id, 'name', v_name, 'gender', v_gender, 'role', v_role, 'active', v_active);
end $$;

-- Kode berlaku 15 menit, sekali pakai. Kode kedaluwarsa dibiarkan (tabel sangat kecil).
create function public.owner_create_pairing_code(p_key text, p_site text, p_label text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; b bytea; code text := ''; i int; exp timestamptz := now() + interval '15 minutes';
begin
  perform private.owner_check(p_key);
  perform private.site_exists(p_site);
  b := uuid_send(gen_random_uuid());
  foreach i in array array[0, 1, 2, 3, 4, 5, 10, 11] loop  -- byte acak penuh (byte 6 & 8 berisi versi/varian UUID)
    code := code || substr(alphabet, (get_byte(b, i) % 32) + 1, 1);
  end loop;
  insert into public.pairing_codes (code_hash, site_id, label, expires_at)
    values (private.hash(code), p_site, left(btrim(coalesce(p_label, '')), 40), exp);
  return jsonb_build_object('code', substr(code, 1, 4) || '-' || substr(code, 5, 4), 'expiresAt', exp);
end $$;

create function public.owner_revoke_device(p_key text, p_device_id uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  perform private.owner_check(p_key);
  update public.api_keys set revoked_at = now()
    where id = p_device_id and role = 'device' and revoked_at is null;
  return found;
end $$;

-- ---------- RPC HP presensi (aplikasi Android) ----------

create function public.device_pair(p_code text, p_label text, p_site text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare norm text; pc public.pairing_codes; new_key text; site_name text;
begin
  norm := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  if length(norm) <> 8 then raise exception 'Kode harus 8 huruf/angka, contoh K7P2-9XQM.' using errcode = '22023'; end if;
  select * into pc from public.pairing_codes
    where code_hash = private.hash(norm) and used_at is null and expires_at > now() for update;
  if pc.code_hash is null then raise exception 'Kode salah atau sudah kedaluwarsa. Buat kode baru di dashboard.' using errcode = '28000'; end if;
  select name into site_name from public.sites where id = pc.site_id;
  if p_site is not null and p_site <> pc.site_id then
    raise exception 'Kode ini untuk %, bukan untuk aplikasi ini.', site_name using errcode = '22023';
  end if;
  update public.pairing_codes set used_at = now() where code_hash = pc.code_hash;
  new_key := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  insert into public.api_keys (key_hash, role, site_id, label, last_seen_at)
    values (private.hash(new_key), 'device', pc.site_id,
            left(coalesce(nullif(btrim(p_label), ''), nullif(pc.label, ''), 'HP presensi'), 40), now());
  return jsonb_build_object('key', new_key, 'siteId', pc.site_id, 'siteName', site_name);
end $$;

create function public.device_sync(p_key text, p_label text, p_app_version text, p_unsynced int, p_settings_version bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare k public.api_keys;
begin
  k := private.device_site(p_key);
  update public.api_keys set last_seen_at = now(),
      app_version = left(coalesce(p_app_version, app_version), 40),
      unsynced = coalesce(p_unsynced, unsynced),
      settings_version = coalesce(p_settings_version, settings_version),
      label = left(coalesce(nullif(btrim(p_label), ''), label), 40)
    where id = k.id;
  return private.site_payload(k.site_id) || jsonb_build_object('serverTime', now());
end $$;

-- p_rows: presensi baru/berubah (tanpa foto). p_deletes: [{employeeId, date}] yang dihapus di HP (salah tekan).
-- Urutan penting: tandai hapus DULU, baru simpan presensi baru. Kalau HP menghapus "salah tekan" lalu absen ulang
-- di hari yang sama, keduanya bisa terkirim dalam satu kiriman; presensi baru harus menang.
create function public.device_upload(p_key text, p_rows jsonb, p_deletes jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare k public.api_keys; n_up int := 0; n_del int := 0;
begin
  k := private.device_site(p_key);
  if p_rows is not null and jsonb_typeof(p_rows) = 'array' and jsonb_array_length(p_rows) > 500 then
    raise exception 'Maks 500 presensi per kiriman.' using errcode = '22023';
  end if;
  if p_deletes is not null and jsonb_typeof(p_deletes) = 'array' then
    update public.attendance a set deleted_at = now()
      from jsonb_to_recordset(p_deletes) as d("employeeId" bigint, "date" date)
      where a.site_id = k.site_id and a.employee_id = d."employeeId" and a.date = d."date" and a.deleted_at is null;
    get diagnostics n_del = row_count;
  end if;
  if p_rows is not null and jsonb_typeof(p_rows) = 'array' then
    insert into public.attendance (site_id, employee_id, date, time_in, ts, status, late_minutes, note,
                                   latitude, longitude, accuracy, distance_m, in_location, location_note, device, uploaded_at, deleted_at)
    select k.site_id, r."employeeId", r."date", coalesce(r."timeIn", ''),
           case when r."timestamp" is null or r."timestamp" <= 0 then null else to_timestamp(r."timestamp" / 1000.0) end,
           r."status", coalesce(r."lateMinutes", 0), left(coalesce(r."note", ''), 200),
           r."latitude", r."longitude", r."accuracy", r."distanceMeters", coalesce(r."inLocation", false),
           left(coalesce(r."locationNote", ''), 100), k.label, now(), null
    from jsonb_to_recordset(p_rows) as r("employeeId" bigint, "date" date, "timeIn" text, "timestamp" bigint, "status" text,
         "lateMinutes" int, "note" text, "latitude" double precision, "longitude" double precision, "accuracy" real,
         "distanceMeters" int, "inLocation" boolean, "locationNote" text)
    on conflict (site_id, employee_id, date) do update set
      time_in = excluded.time_in, ts = excluded.ts, status = excluded.status, late_minutes = excluded.late_minutes,
      note = excluded.note, latitude = excluded.latitude, longitude = excluded.longitude, accuracy = excluded.accuracy,
      distance_m = excluded.distance_m, in_location = excluded.in_location, location_note = excluded.location_note,
      device = excluded.device, uploaded_at = now(), deleted_at = null;
    get diagnostics n_up = row_count;
  end if;
  update public.api_keys set last_seen_at = now() where id = k.id;
  return jsonb_build_object('upserted', n_up, 'deleted', n_del);
end $$;

create function public.device_save_settings(p_key text, p_data jsonb, p_base_version bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare k public.api_keys; cur bigint;
begin
  k := private.device_site(p_key);
  perform private.validate_settings(p_data);
  select version into cur from public.site_settings where site_id = k.site_id for update;
  if cur is null then
    if coalesce(p_base_version, 0) <> 0 then return jsonb_build_object('ok', false, 'version', 0); end if;
    insert into public.site_settings (site_id, version, data, updated_by) values (k.site_id, 1, p_data, 'HP: ' || k.label)
      on conflict (site_id) do nothing;
    if not found then return jsonb_build_object('ok', false, 'version', (select version from public.site_settings where site_id = k.site_id)); end if;
    return jsonb_build_object('ok', true, 'version', 1);
  end if;
  if coalesce(p_base_version, -1) <> cur then return jsonb_build_object('ok', false, 'version', cur); end if;
  update public.site_settings set version = cur + 1, data = p_data, updated_at = now(), updated_by = 'HP: ' || k.label
    where site_id = k.site_id;
  return jsonb_build_object('ok', true, 'version', cur + 1);
end $$;

create function public.device_seed_employees(p_key text, p_rows jsonb) returns int
language plpgsql security definer set search_path = '' as $$
declare k public.api_keys; n int := 0;
begin
  k := private.device_site(p_key);
  if p_rows is null or jsonb_typeof(p_rows) <> 'array' or jsonb_array_length(p_rows) > 200 then
    raise exception 'Daftar karyawan tidak valid.' using errcode = '22023'; end if;
  if exists (select 1 from public.employees where site_id = k.site_id) then return 0; end if;
  insert into public.employees (site_id, id, name, gender, role, active)
  select k.site_id, r.id, btrim(r.name), r.gender, coalesce(btrim(r.role), ''), coalesce(r.active, true)
  from jsonb_to_recordset(p_rows) as r(id bigint, name text, gender text, role text, active boolean)
  on conflict (site_id, id) do nothing;
  get diagnostics n = row_count;
  return n;
end $$;

-- "Putuskan HP ini" dari HP sendiri: kunci HP ditandai diputus (hapus lunak), sama seperti owner_revoke_device.
create function public.device_unpair(p_key text) returns boolean
language plpgsql security definer set search_path = '' as $$
declare k public.api_keys;
begin
  k := private.device_site(p_key);
  update public.api_keys set revoked_at = now() where id = k.id and revoked_at is null;
  return found;
end $$;

-- ---------- hak akses: hanya RPC di atas yang bisa dipanggil dari aplikasi/dashboard ----------
revoke execute on all functions in schema public from public, anon, authenticated;
revoke execute on all functions in schema private from public, anon, authenticated;
grant execute on function
  public.owner_sites(text),
  public.owner_overview(text, text, date, date),
  public.owner_save_settings(text, text, jsonb, bigint),
  public.owner_save_employee(text, text, jsonb),
  public.owner_create_pairing_code(text, text, text),
  public.owner_revoke_device(text, uuid),
  public.device_pair(text, text, text),
  public.device_sync(text, text, text, int, bigint),
  public.device_upload(text, jsonb, jsonb),
  public.device_save_settings(text, jsonb, bigint),
  public.device_seed_employees(text, jsonb),
  public.device_unpair(text)
to anon;
