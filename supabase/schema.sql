-- Tethr Link: optional accounts (Supabase project "tethr-link").
-- Every row belongs to the signed-in user who wrote it; nobody else can read it.

create table if not exists public.pcs (
  pc_id text primary key,
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  name text not null,
  fingerprint text not null,
  host text,
  port int,
  pair_token text,            -- works like the QR code's token, once; the PC replaces it after use
  updated_at timestamptz not null default now()
);
alter table public.pcs enable row level security;
drop policy if exists "own pcs" on public.pcs;
create policy "own pcs" on public.pcs for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create table if not exists public.user_settings (
  user_id uuid primary key default auth.uid() references auth.users on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);
alter table public.user_settings enable row level security;
drop policy if exists "own settings" on public.user_settings;
create policy "own settings" on public.user_settings for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

revoke all on public.pcs, public.user_settings from anon;
grant select, insert, update, delete on public.pcs, public.user_settings to authenticated;
