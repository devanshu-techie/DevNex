-- =====================================================================
-- DevNex — Multi-tenant foundation migration
-- Powered by Devanshu
--
-- Shared schema, company_id on every business row, enforced by RLS.
-- All important state changes (claim, submit, approve, reassign, role
-- change) run as SECURITY DEFINER functions, never as raw client updates.
-- Run on a fresh Supabase project:  supabase db push   (or paste in SQL editor)
-- =====================================================================

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------
-- 0. Enumerations kept as CHECK constraints (easier to evolve than enums)
-- ---------------------------------------------------------------------
-- task status lifecycle (spec §6):
--   inbox → evaluation → open → assigned → in_progress → blocked
--   → submitted → changes_requested → approved → published | cancelled

-- ---------------------------------------------------------------------
-- 1. Platform tables (no tenant)
-- ---------------------------------------------------------------------
create table public.profiles (
  id           uuid primary key references auth.users(id) on delete cascade,
  email        text not null,
  full_name    text not null default '',
  avatar_hue   int  not null default 260,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create table public.platform_admins (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  role       text not null default 'owner' check (role in ('owner','operator')),
  created_at timestamptz not null default now()
);

create table public.companies (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (length(name) between 2 and 80),
  slug        text not null unique check (slug ~ '^[a-z0-9-]{3,40}$'),
  industry    text not null default 'general'
              check (industry in ('general','newsroom','agency','software')),
  status      text not null default 'active'
              check (status in ('active','suspended','deactivated')),
  plan        text not null default 'free',
  brand_color text not null default '#7C5CFF',
  logo_url    text,
  created_by  uuid not null references auth.users(id),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table public.tenant_usage_daily (
  company_id   uuid not null references public.companies(id) on delete cascade,
  day          date not null,
  task_count   int  not null default 0,
  message_count int not null default 0,
  active_users int  not null default 0,
  primary key (company_id, day)
);

-- ---------------------------------------------------------------------
-- 2. Company setup
-- ---------------------------------------------------------------------
create table public.company_settings (
  company_id                uuid primary key references public.companies(id) on delete cascade,
  member_visibility         text not null default 'department'
                            check (member_visibility in ('own','department','company')),
  mfa_required_rank         int  not null default 90,   -- owner/admin need MFA
  claim_expiry_hours        int  not null default 24,
  approval_reminder_hours   int  not null default 12,
  default_approval_chain    text[] not null default array['lead','head'],
  editorial_weights         jsonb not null default
    '{"F":0.25,"I":0.25,"U":0.20,"A":0.15,"R":0.10,"V":0.05}',
  assignment_weights        jsonb not null default
    '{"K":0.40,"C":0.25,"D":0.20,"T":0.15}',
  updated_at                timestamptz not null default now(),
  updated_by                uuid references auth.users(id)
);

create table public.company_modules (
  company_id uuid not null references public.companies(id) on delete cascade,
  module     text not null check (module in ('coverage','publishing','ideas','ops_log')),
  enabled    boolean not null default false,
  primary key (company_id, module)
);

create table public.company_roles (
  id          uuid primary key default gen_random_uuid(),
  company_id  uuid not null references public.companies(id) on delete cascade,
  key         text not null,                 -- owner, admin, head, lead, member, custom…
  name        text not null,
  rank        int  not null check (rank between 0 and 100),
  is_system   boolean not null default false,
  created_at  timestamptz not null default now(),
  unique (company_id, key)
);

create table public.role_permissions (
  role_id     uuid not null references public.company_roles(id) on delete cascade,
  company_id  uuid not null references public.companies(id) on delete cascade,
  permission  text not null,
  primary key (role_id, permission)
);
create index idx_role_permissions_company on public.role_permissions (company_id, role_id);

create table public.company_departments (
  id           uuid primary key default gen_random_uuid(),
  company_id   uuid not null references public.companies(id) on delete cascade,
  name         text not null,
  head_user_id uuid references auth.users(id),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  updated_by   uuid references auth.users(id),
  unique (company_id, name)
);

create table public.company_memberships (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  role_id       uuid not null references public.company_roles(id),
  department_id uuid references public.company_departments(id) on delete set null,
  title         text not null default '',
  skills        text[] not null default '{}',
  availability  text not null default 'available'
                check (availability in ('available','busy','leave')),
  status        text not null default 'active'
                check (status in ('active','suspended','removed')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  updated_by    uuid references auth.users(id),
  unique (company_id, user_id)
);
create index idx_memberships_user
  on public.company_memberships (user_id, company_id) where status = 'active';
create index idx_memberships_company_dept
  on public.company_memberships (company_id, department_id);

create table public.company_invitations (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  email         text not null,
  role_id       uuid not null references public.company_roles(id),
  department_id uuid references public.company_departments(id) on delete set null,
  token_hash    text not null unique,
  status        text not null default 'pending'
                check (status in ('pending','accepted','revoked','expired')),
  invited_by    uuid not null references auth.users(id),
  expires_at    timestamptz not null default now() + interval '7 days',
  accepted_by   uuid references auth.users(id),
  created_at    timestamptz not null default now()
);
create index idx_invitations_company on public.company_invitations (company_id, status);

-- Time-limited, consented, audited platform support access (spec §4.6)
create table public.support_access_grants (
  id               uuid primary key default gen_random_uuid(),
  company_id       uuid not null references public.companies(id) on delete cascade,
  platform_user_id uuid not null references auth.users(id),
  granted_by       uuid not null references auth.users(id),
  reason           text not null,
  expires_at       timestamptz not null,
  revoked_at       timestamptz,
  created_at       timestamptz not null default now()
);
create index idx_support_grants on public.support_access_grants (company_id, platform_user_id);

-- ---------------------------------------------------------------------
-- 3. Work (core)
-- ---------------------------------------------------------------------
create table public.projects (
  id          uuid primary key default gen_random_uuid(),
  company_id  uuid not null references public.companies(id) on delete cascade,
  name        text not null,
  description text,
  status      text not null default 'active' check (status in ('active','archived')),
  created_by  uuid not null references auth.users(id),
  version     int  not null default 1,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  updated_by  uuid references auth.users(id)
);
create index idx_projects_company on public.projects (company_id, status);

create table public.tasks (
  id                     uuid primary key default gen_random_uuid(),
  company_id             uuid not null references public.companies(id) on delete cascade,
  project_id             uuid references public.projects(id) on delete set null,
  parent_task_id         uuid references public.tasks(id) on delete cascade,
  department_id          uuid references public.company_departments(id) on delete set null,
  title                  text not null check (length(title) between 1 and 200),
  description            text not null default '',
  status                 text not null default 'inbox' check (status in
                           ('inbox','evaluation','open','assigned','in_progress','blocked',
                            'submitted','changes_requested','approved','published','cancelled')),
  priority               text not null default 'normal'
                           check (priority in ('critical','high','normal','low')),
  created_by             uuid not null references auth.users(id),
  assigned_to            uuid references auth.users(id),
  is_open_pool           boolean not null default false,
  eligible_skill         text,
  start_date             date,
  due_at                 timestamptz,
  estimated_effort       numeric(6,2),
  approval_chain         text[] not null default array['lead','head'],
  approval_stage         int  not null default 0,
  requires_independent_approval boolean not null default true,
  blocked_reason         text,
  version                int  not null default 1,
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now(),
  updated_by             uuid references auth.users(id)
);
create index idx_tasks_company_status   on public.tasks (company_id, status, updated_at desc);
create index idx_tasks_company_assignee on public.tasks (company_id, assigned_to, status);
create index idx_tasks_company_dept     on public.tasks (company_id, department_id, status);
create index idx_tasks_company_parent   on public.tasks (company_id, parent_task_id);
create index idx_tasks_open_pool        on public.tasks (company_id, department_id)
  where is_open_pool and status = 'open';
create index idx_tasks_company_due      on public.tasks (company_id, due_at) where due_at is not null;

create table public.task_checklists (
  id          uuid primary key default gen_random_uuid(),
  company_id  uuid not null references public.companies(id) on delete cascade,
  task_id     uuid not null references public.tasks(id) on delete cascade,
  label       text not null,
  is_done     boolean not null default false,
  position    int not null default 0,
  version     int not null default 1,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references auth.users(id)
);
create index idx_checklists_task on public.task_checklists (company_id, task_id, position);

create table public.task_dependencies (
  company_id     uuid not null references public.companies(id) on delete cascade,
  task_id        uuid not null references public.tasks(id) on delete cascade,
  depends_on_id  uuid not null references public.tasks(id) on delete cascade,
  primary key (task_id, depends_on_id),
  check (task_id <> depends_on_id)
);

create table public.task_claims (
  id          uuid primary key default gen_random_uuid(),
  company_id  uuid not null references public.companies(id) on delete cascade,
  task_id     uuid not null references public.tasks(id) on delete cascade,
  user_id     uuid not null references auth.users(id),
  claimed_at  timestamptz not null default now(),
  released_at timestamptz,
  release_reason text
);
create index idx_claims_task on public.task_claims (company_id, task_id);

create table public.task_approvals (
  id          uuid primary key default gen_random_uuid(),
  company_id  uuid not null references public.companies(id) on delete cascade,
  task_id     uuid not null references public.tasks(id) on delete cascade,
  stage       int  not null,
  stage_role  text not null,
  approver_id uuid not null references auth.users(id),
  decision    text not null check (decision in ('approved','changes_requested')),
  note        text,
  created_at  timestamptz not null default now()
);
create index idx_approvals_task on public.task_approvals (company_id, task_id, created_at);

create table public.task_activity (
  id          bigint generated always as identity primary key,
  company_id  uuid not null references public.companies(id) on delete cascade,
  task_id     uuid not null references public.tasks(id) on delete cascade,
  actor_id    uuid references auth.users(id),
  action      text not null,
  detail      jsonb not null default '{}',
  created_at  timestamptz not null default now()
);
create index idx_activity_task on public.task_activity (company_id, task_id, created_at desc);

create table public.task_comments (
  id          uuid primary key,                       -- client-generated UUID (idempotent retry)
  company_id  uuid not null references public.companies(id) on delete cascade,
  task_id     uuid not null references public.tasks(id) on delete cascade,
  author_id   uuid not null references auth.users(id),
  body        text not null check (length(body) between 1 and 4000),
  created_at  timestamptz not null default now()
);
create index idx_comments_task on public.task_comments (company_id, task_id, created_at);

-- ---------------------------------------------------------------------
-- 4. Communication (text only — no media, by design)
-- ---------------------------------------------------------------------
create table public.chat_channels (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  name          text not null,
  kind          text not null default 'channel' check (kind in ('channel','dm','announcement')),
  department_id uuid references public.company_departments(id) on delete cascade,
  is_private    boolean not null default false,
  created_by    uuid not null references auth.users(id),
  created_at    timestamptz not null default now(),
  unique (company_id, name)
);

create table public.chat_members (
  company_id   uuid not null references public.companies(id) on delete cascade,
  channel_id   uuid not null references public.chat_channels(id) on delete cascade,
  user_id      uuid not null references auth.users(id) on delete cascade,
  last_read_at timestamptz not null default now(),
  primary key (channel_id, user_id)
);
create index idx_chat_members_user on public.chat_members (company_id, user_id);

create table public.messages (
  id          uuid primary key,                       -- client-generated UUID
  company_id  uuid not null references public.companies(id) on delete cascade,
  channel_id  uuid not null references public.chat_channels(id) on delete cascade,
  sender_id   uuid not null references auth.users(id),
  body        text not null check (length(body) between 1 and 4000),
  mentions    uuid[] not null default '{}',
  edited_at   timestamptz,
  created_at  timestamptz not null default now()
);
create index idx_messages_company_channel on public.messages (company_id, channel_id, created_at desc);

create table public.notifications (
  id          bigint generated always as identity primary key,
  company_id  uuid not null references public.companies(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  kind        text not null,
  title       text not null,
  body        text not null default '',
  task_id     uuid references public.tasks(id) on delete cascade,
  read_at     timestamptz,
  created_at  timestamptz not null default now()
);
create index idx_notifications_user on public.notifications (user_id, company_id, created_at desc);

create table public.device_tokens (
  user_id     uuid not null references auth.users(id) on delete cascade,
  token       text not null,
  platform    text not null default 'android',
  updated_at  timestamptz not null default now(),
  primary key (user_id, token)
);

-- ---------------------------------------------------------------------
-- 5. Governance
-- ---------------------------------------------------------------------
create table public.audit_logs (
  id          bigint generated always as identity primary key,
  company_id  uuid references public.companies(id) on delete cascade,
  actor_id    uuid,
  action      text not null,
  entity      text not null,
  entity_id   text,
  detail      jsonb not null default '{}',
  created_at  timestamptz not null default now()
);
create index idx_audit_company on public.audit_logs (company_id, created_at desc);

-- ---------------------------------------------------------------------
-- 6. Coverage Desk (optional module)
-- ---------------------------------------------------------------------
create table public.coverage_events (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  name          text not null,
  sport         text not null default '',
  venue         text not null default '',
  event_date    date not null,
  importance    text not null default 'normal' check (importance in ('critical','high','normal','low')),
  status        text not null default 'planned' check (status in ('planned','live','covered','archived')),
  created_by    uuid not null references auth.users(id),
  version       int not null default 1,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  updated_by    uuid references auth.users(id)
);
create index idx_coverage_events on public.coverage_events (company_id, event_date desc);

create table public.coverage_submissions (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  event_id      uuid not null references public.coverage_events(id) on delete cascade,
  reporter_id   uuid not null references auth.users(id),
  athlete       text not null default '',
  result        text not null default '',
  quotes        text not null default '',
  news_angle    text not null default '',
  media_links   text[] not null default '{}',     -- external references only
  verified      boolean not null default false,
  score_f int check (score_f between 0 and 5), score_i int check (score_i between 0 and 5),
  score_u int check (score_u between 0 and 5), score_a int check (score_a between 0 and 5),
  score_r int check (score_r between 0 and 5), score_v int check (score_v between 0 and 5),
  editorial_status text not null default 'submitted' check (editorial_status in
    ('submitted','selected','high_priority','waiting_info','rejected','scheduled')),
  override_reason text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  updated_by    uuid references auth.users(id)
);
create index idx_coverage_submissions on public.coverage_submissions (company_id, event_id, created_at desc);

-- =====================================================================
-- 7. Access helper functions (SECURITY DEFINER, stable → cached per statement)
-- =====================================================================
create or replace function public.is_platform_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from platform_admins where user_id = (select auth.uid()));
$$;

create or replace function public.is_member(cid uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from company_memberships m
    join companies c on c.id = m.company_id
    where m.company_id = cid and m.user_id = (select auth.uid())
      and m.status = 'active' and c.status = 'active');
$$;

create or replace function public.has_permission(cid uuid, perm text)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from company_memberships m
    join companies c        on c.id = m.company_id
    join role_permissions rp on rp.role_id = m.role_id
    where m.company_id = cid and m.user_id = (select auth.uid())
      and m.status = 'active' and c.status = 'active' and rp.permission = perm);
$$;

create or replace function public.my_rank(cid uuid)
returns int language sql stable security definer set search_path = public as $$
  select coalesce(max(r.rank), -1) from company_memberships m
  join company_roles r on r.id = m.role_id
  join companies c on c.id = m.company_id
  where m.company_id = cid and m.user_id = (select auth.uid())
    and m.status = 'active' and c.status = 'active';
$$;

create or replace function public.my_department(cid uuid)
returns uuid language sql stable security definer set search_path = public as $$
  select department_id from company_memberships
  where company_id = cid and user_id = (select auth.uid()) and status = 'active';
$$;

create or replace function public.has_support_access(cid uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.is_platform_admin() and exists (
    select 1 from support_access_grants g
    where g.company_id = cid and g.platform_user_id = (select auth.uid())
      and g.revoked_at is null and g.expires_at > now());
$$;

create or replace function public.can_view_task(cid uuid, dept uuid, assignee uuid, creator uuid, open_pool boolean, st text)
returns boolean language sql stable security definer set search_path = public as $$
  select
    public.has_support_access(cid)
    or (public.is_member(cid) and (
         public.has_permission(cid, 'task.view_all')
      or assignee = (select auth.uid())
      or creator  = (select auth.uid())
      or (open_pool and st = 'open')
      or (coalesce((select member_visibility from company_settings where company_id = cid), 'department') = 'company')
      or (dept is not null and dept = public.my_department(cid)
          and coalesce((select member_visibility from company_settings where company_id = cid), 'department') = 'department')
    ));
$$;

create or replace function public.can_access_channel(ch uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from chat_channels c
    where c.id = ch and public.is_member(c.company_id)
      and (not c.is_private
           or exists (select 1 from chat_members cm where cm.channel_id = c.id and cm.user_id = (select auth.uid()))));
$$;

-- Internal helpers (not exposed via API)
create or replace function public._audit(cid uuid, act text, ent text, eid text, det jsonb default '{}')
returns void language sql security definer set search_path = public as $$
  insert into audit_logs (company_id, actor_id, action, entity, entity_id, detail)
  values (cid, (select auth.uid()), act, ent, eid, det);
$$;

create or replace function public._notify(cid uuid, uid uuid, k text, t text, b text, tid uuid)
returns void language sql security definer set search_path = public as $$
  insert into notifications (company_id, user_id, kind, title, body, task_id)
  select cid, uid, k, t, b, tid where uid is not null and uid is distinct from (select auth.uid());
$$;

create or replace function public._task_log(cid uuid, tid uuid, act text, det jsonb default '{}')
returns void language sql security definer set search_path = public as $$
  insert into task_activity (company_id, task_id, actor_id, action, detail)
  values (cid, tid, (select auth.uid()), act, det);
$$;

-- =====================================================================
-- 8. Triggers: timestamps, versions, profile bootstrap, task audit
-- =====================================================================
create or replace function public._touch()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  if tg_op = 'UPDATE' then
    begin new.updated_by := coalesce((select auth.uid()), new.updated_by);
    exception when undefined_column then null; end;
  end if;
  return new;
end $$;

create or replace function public._bump_version()
returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' then new.version := old.version + 1; end if;
  return new;
end $$;

do $$
declare t text;
begin
  foreach t in array array['profiles','companies','company_settings','company_departments',
                           'company_memberships','projects','tasks','task_checklists',
                           'coverage_events','coverage_submissions']
  loop
    execute format('create trigger trg_touch before update on public.%I for each row execute function public._touch()', t);
  end loop;
  foreach t in array array['projects','tasks','task_checklists','coverage_events']
  loop
    execute format('create trigger trg_version before update on public.%I for each row execute function public._bump_version()', t);
  end loop;
end $$;

-- Create a profile automatically when a user signs up
create or replace function public._on_auth_user_created()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into profiles (id, email, full_name)
  values (new.id, coalesce(new.email, ''),
          coalesce(new.raw_user_meta_data->>'full_name', split_part(coalesce(new.email,''), '@', 1)))
  on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public._on_auth_user_created();

-- Audit task create / delete / direct edits
create or replace function public._audit_tasks()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    perform _audit(new.company_id, 'task.create', 'task', new.id::text, jsonb_build_object('title', new.title));
    perform _task_log(new.company_id, new.id, 'created');
    if new.assigned_to is not null then
      perform _notify(new.company_id, new.assigned_to, 'assignment', 'New task assigned', new.title, new.id);
    end if;
    return new;
  elsif tg_op = 'DELETE' then
    perform _audit(old.company_id, 'task.delete', 'task', old.id::text, jsonb_build_object('title', old.title));
    return old;
  end if;
  return new;
end $$;
create trigger trg_audit_tasks after insert or delete on public.tasks
  for each row execute function public._audit_tasks();

-- Membership changes are always audited
create or replace function public._audit_memberships()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  perform _audit(coalesce(new.company_id, old.company_id), 'membership.' || lower(tg_op), 'membership',
                 coalesce(new.id, old.id)::text,
                 jsonb_build_object('user_id', coalesce(new.user_id, old.user_id),
                                    'role_id', coalesce(new.role_id, old.role_id),
                                    'status', coalesce(new.status, old.status)));
  return coalesce(new, old);
end $$;
create trigger trg_audit_memberships after insert or update or delete on public.company_memberships
  for each row execute function public._audit_memberships();

-- Parent/child integrity: subtasks must stay in the parent's company
create or replace function public._check_task_tenant()
returns trigger language plpgsql as $$
begin
  if new.parent_task_id is not null and not exists
     (select 1 from public.tasks p where p.id = new.parent_task_id and p.company_id = new.company_id) then
    raise exception 'parent task belongs to another company';
  end if;
  if new.department_id is not null and not exists
     (select 1 from public.company_departments d where d.id = new.department_id and d.company_id = new.company_id) then
    raise exception 'department belongs to another company';
  end if;
  if new.project_id is not null and not exists
     (select 1 from public.projects p where p.id = new.project_id and p.company_id = new.company_id) then
    raise exception 'project belongs to another company';
  end if;
  if new.assigned_to is not null and not exists
     (select 1 from public.company_memberships m where m.user_id = new.assigned_to
        and m.company_id = new.company_id and m.status = 'active') then
    raise exception 'assignee is not an active member of this company';
  end if;
  return new;
end $$;
create trigger trg_task_tenant before insert or update of parent_task_id, department_id, project_id, assigned_to
  on public.tasks for each row execute function public._check_task_tenant();

-- =====================================================================
-- 9. Business functions (RPC) — the only way to change important state
-- =====================================================================

-- 9.1 Create company from a template; caller becomes owner
create or replace function public.create_company(p_name text, p_slug text, p_industry text default 'general')
returns uuid language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := (select auth.uid());
  v_cid uuid;
  v_role record;
  v_owner_role uuid;
  v_all_perms text[] := array[
    'company.manage','member.invite','member.manage','department.manage','role.manage','module.manage',
    'task.create','task.request','task.edit','task.assign','task.claim','task.approve','task.view_all',
    'report.company','report.department','audit.view','chat.announce','coverage.manage','coverage.submit'];
begin
  if v_uid is null then raise exception 'not authenticated'; end if;

  insert into companies (name, slug, industry, created_by)
  values (p_name, lower(p_slug), p_industry, v_uid) returning id into v_cid;

  insert into company_settings (company_id) values (v_cid);

  insert into company_modules (company_id, module, enabled) values
    (v_cid, 'coverage',   p_industry = 'newsroom'),
    (v_cid, 'publishing', p_industry in ('newsroom','agency')),
    (v_cid, 'ideas',      true),
    (v_cid, 'ops_log',    true);

  -- Role template
  for v_role in
    select * from (values
      ('owner',  'Company Owner',   100, v_all_perms),
      ('admin',  'Company Admin',    90, v_all_perms),
      ('head',   'Department Head',  70, array['task.create','task.edit','task.assign','task.claim','task.approve',
                                               'task.view_all','report.department','coverage.manage','coverage.submit',
                                               'member.invite']),
      ('lead',   'Team Lead',        50, array['task.create','task.edit','task.assign','task.claim','task.approve',
                                               'report.department','coverage.submit']),
      ('member', 'Member',           10, array['task.request','task.claim','coverage.submit'])
    ) as t(key, name, rank, perms)
  loop
    with r as (
      insert into company_roles (company_id, key, name, rank, is_system)
      values (v_cid, v_role.key, v_role.name, v_role.rank, true) returning id)
    insert into role_permissions (role_id, company_id, permission)
    select r.id, v_cid, unnest(v_role.perms) from r;
  end loop;

  select id into v_owner_role from company_roles where company_id = v_cid and key = 'owner';

  -- Default departments by industry
  insert into company_departments (company_id, name)
  select v_cid, d from unnest(case p_industry
    when 'newsroom' then array['Reporting','Editorial','Video','Creative','Social Media','Website']
    when 'agency'   then array['Accounts','Creative','Performance','Content','Social Media']
    when 'software' then array['Engineering','Product','Design','QA','Operations']
    else                 array['Operations','Sales','Support'] end) d;

  insert into company_memberships (company_id, user_id, role_id, title)
  values (v_cid, v_uid, v_owner_role, 'Owner');

  insert into chat_channels (company_id, name, kind, created_by) values
    (v_cid, 'general', 'channel', v_uid),
    (v_cid, 'announcements', 'announcement', v_uid);

  perform _audit(v_cid, 'company.create', 'company', v_cid::text, jsonb_build_object('name', p_name));
  return v_cid;
end $$;

-- 9.2 Invitations: returns the plain token once; only its hash is stored
create or replace function public.create_invitation(p_company uuid, p_email text, p_role_key text, p_department uuid default null)
returns text language plpgsql security definer set search_path = public, extensions as $$
declare
  v_token text := encode(extensions.gen_random_bytes(18), 'hex');
  v_role  record;
begin
  if not has_permission(p_company, 'member.invite') then raise exception 'forbidden'; end if;
  select id, rank into v_role from company_roles where company_id = p_company and key = p_role_key;
  if v_role.id is null then raise exception 'unknown role'; end if;
  if v_role.rank >= my_rank(p_company) and my_rank(p_company) < 100 then
    raise exception 'cannot invite at or above your own rank';
  end if;
  insert into company_invitations (company_id, email, role_id, department_id, token_hash, invited_by)
  values (p_company, lower(p_email), v_role.id, p_department,
          encode(extensions.digest(v_token, 'sha256'), 'hex'), (select auth.uid()));
  perform _audit(p_company, 'invitation.create', 'invitation', lower(p_email), jsonb_build_object('role', p_role_key));
  return v_token;
end $$;

create or replace function public.accept_invitation(p_token text)
returns uuid language plpgsql security definer set search_path = public, extensions as $$
declare
  v_uid uuid := (select auth.uid());
  v_inv company_invitations;
  v_email text;
begin
  if v_uid is null then raise exception 'not authenticated'; end if;
  select email into v_email from profiles where id = v_uid;
  select * into v_inv from company_invitations
   where token_hash = encode(extensions.digest(p_token, 'sha256'), 'hex')
   for update;
  if v_inv.id is null or v_inv.status <> 'pending' then raise exception 'invalid invitation'; end if;
  if v_inv.expires_at < now() then
    update company_invitations set status = 'expired' where id = v_inv.id;
    raise exception 'invitation expired';
  end if;
  if lower(v_email) <> v_inv.email then raise exception 'invitation was sent to a different email'; end if;

  insert into company_memberships (company_id, user_id, role_id, department_id)
  values (v_inv.company_id, v_uid, v_inv.role_id, v_inv.department_id)
  on conflict (company_id, user_id) do update set status = 'active', role_id = excluded.role_id;

  insert into chat_members (company_id, channel_id, user_id)
  select v_inv.company_id, c.id, v_uid from chat_channels c
  where c.company_id = v_inv.company_id and not c.is_private
  on conflict do nothing;

  update company_invitations set status = 'accepted', accepted_by = v_uid where id = v_inv.id;
  perform _audit(v_inv.company_id, 'invitation.accept', 'invitation', v_inv.id::text);
  return v_inv.company_id;
end $$;

-- 9.3 Role changes: never your own, never above your rank
create or replace function public.set_member_role(p_membership uuid, p_role_key text)
returns void language plpgsql security definer set search_path = public as $$
declare v_m company_memberships; v_role record;
begin
  select * into v_m from company_memberships where id = p_membership;
  if v_m.id is null or not has_permission(v_m.company_id, 'member.manage') then raise exception 'forbidden'; end if;
  if v_m.user_id = (select auth.uid()) then raise exception 'you cannot change your own role'; end if;
  select id, rank into v_role from company_roles where company_id = v_m.company_id and key = p_role_key;
  if v_role.id is null then raise exception 'unknown role'; end if;
  if (v_role.rank >= my_rank(v_m.company_id) or
      (select rank from company_roles where id = v_m.role_id) >= my_rank(v_m.company_id))
     and my_rank(v_m.company_id) < 100 then
    raise exception 'cannot manage members at or above your own rank';
  end if;
  update company_memberships set role_id = v_role.id where id = p_membership;
end $$;

create or replace function public.set_member_status(p_membership uuid, p_status text)
returns void language plpgsql security definer set search_path = public as $$
declare v_m company_memberships;
begin
  select * into v_m from company_memberships where id = p_membership;
  if v_m.id is null or not has_permission(v_m.company_id, 'member.manage') then raise exception 'forbidden'; end if;
  if v_m.user_id = (select auth.uid()) then raise exception 'you cannot change your own status'; end if;
  if (select rank from company_roles where id = v_m.role_id) >= my_rank(v_m.company_id) then
    raise exception 'cannot manage members at or above your own rank';
  end if;
  update company_memberships set status = p_status where id = p_membership;
end $$;

create or replace function public.set_member_department(p_membership uuid, p_department uuid)
returns void language plpgsql security definer set search_path = public as $$
declare v_m company_memberships;
begin
  select * into v_m from company_memberships where id = p_membership;
  if v_m.id is null or not has_permission(v_m.company_id, 'member.manage') then raise exception 'forbidden'; end if;
  if p_department is not null and not exists
     (select 1 from company_departments where id = p_department and company_id = v_m.company_id) then
    raise exception 'department belongs to another company';
  end if;
  update company_memberships set department_id = p_department where id = p_membership;
end $$;

-- 9.4 Atomic claim (spec §5). The conditional UPDATE is the lock.
create or replace function public.claim_task(p_task_id uuid)
returns text language plpgsql security definer set search_path = public as $$
declare
  v_uid  uuid := (select auth.uid());
  v_task tasks;
  v_rows int;
  v_m    company_memberships;
begin
  select * into v_task from tasks where id = p_task_id;
  if v_task.id is null or not has_permission(v_task.company_id, 'task.claim') then
    return 'FORBIDDEN';
  end if;
  select * into v_m from company_memberships
   where company_id = v_task.company_id and user_id = v_uid and status = 'active';
  -- eligibility: department and skill group
  if v_task.department_id is not null and v_m.department_id is distinct from v_task.department_id
     and not has_permission(v_task.company_id, 'task.view_all') then
    return 'NOT_ELIGIBLE';
  end if;
  if v_task.eligible_skill is not null and not (v_task.eligible_skill = any(v_m.skills))
     and not has_permission(v_task.company_id, 'task.view_all') then
    return 'NOT_ELIGIBLE';
  end if;

  update tasks
     set assigned_to = v_uid, status = 'assigned', is_open_pool = false
   where id = p_task_id and status = 'open' and is_open_pool and assigned_to is null;
  get diagnostics v_rows = row_count;
  if v_rows = 0 then return 'CLAIM_FAILED'; end if;

  insert into task_claims (company_id, task_id, user_id) values (v_task.company_id, p_task_id, v_uid);
  perform _task_log(v_task.company_id, p_task_id, 'claimed');
  perform _audit(v_task.company_id, 'task.claim', 'task', p_task_id::text);
  perform _notify(v_task.company_id, v_task.created_by, 'claim', 'Task claimed', v_task.title, p_task_id);
  return 'CLAIM_SUCCESS';
end $$;

-- 9.5 Release / drop (reason required)
create or replace function public.release_task(p_task_id uuid, p_reason text)
returns void language plpgsql security definer set search_path = public as $$
declare v_task tasks;
begin
  if coalesce(trim(p_reason), '') = '' then raise exception 'a reason is required'; end if;
  select * into v_task from tasks where id = p_task_id for update;
  if v_task.id is null then raise exception 'not found'; end if;
  if not (v_task.assigned_to = (select auth.uid()) or has_permission(v_task.company_id, 'task.assign')) then
    raise exception 'forbidden';
  end if;
  update tasks set assigned_to = null, status = 'open', is_open_pool = true where id = p_task_id;
  update task_claims set released_at = now(), release_reason = p_reason
   where task_id = p_task_id and released_at is null;
  perform _task_log(v_task.company_id, p_task_id, 'released', jsonb_build_object('reason', p_reason));
  perform _audit(v_task.company_id, 'task.release', 'task', p_task_id::text, jsonb_build_object('reason', p_reason));
  perform _notify(v_task.company_id, v_task.created_by, 'release', 'Task returned to pool', v_task.title, p_task_id);
end $$;

-- 9.6 Assign / reassign (managers)
create or replace function public.assign_task(p_task_id uuid, p_user uuid, p_expected_version int)
returns int language plpgsql security definer set search_path = public as $$
declare v_task tasks; v_new int;
begin
  select * into v_task from tasks where id = p_task_id for update;
  if v_task.id is null or not has_permission(v_task.company_id, 'task.assign') then raise exception 'forbidden'; end if;
  if v_task.version <> p_expected_version then raise exception 'VERSION_CONFLICT'; end if;
  update tasks set assigned_to = p_user, is_open_pool = false,
         status = case when status in ('inbox','evaluation','open') then 'assigned' else status end
   where id = p_task_id returning version into v_new;
  perform _task_log(v_task.company_id, p_task_id,
                    case when v_task.assigned_to is null then 'assigned' else 'reassigned' end,
                    jsonb_build_object('to', p_user, 'from', v_task.assigned_to));
  perform _audit(v_task.company_id, 'task.assign', 'task', p_task_id::text, jsonb_build_object('to', p_user));
  perform _notify(v_task.company_id, p_user, 'assignment', 'New task assigned', v_task.title, p_task_id);
  return v_new;
end $$;

-- 9.7 Status transitions with optimistic concurrency
create or replace function public.set_task_status(p_task_id uuid, p_status text, p_expected_version int, p_note text default null)
returns int language plpgsql security definer set search_path = public as $$
declare
  v_task tasks; v_new int;
  v_uid uuid := (select auth.uid());
  v_is_manager boolean;
begin
  select * into v_task from tasks where id = p_task_id for update;
  if v_task.id is null or not is_member(v_task.company_id) then raise exception 'forbidden'; end if;
  if v_task.version <> p_expected_version then raise exception 'VERSION_CONFLICT'; end if;
  v_is_manager := has_permission(v_task.company_id, 'task.assign');

  -- Approval states are reachable only through decide_approval
  if p_status in ('approved','changes_requested') then raise exception 'use decide_approval'; end if;
  if p_status = 'published' and not (v_task.status = 'approved' and v_is_manager) then
    raise exception 'only approved work can be published by a manager';
  end if;

  if not v_is_manager then
    if v_task.assigned_to is distinct from v_uid then raise exception 'forbidden'; end if;
    if not ((v_task.status, p_status) in (
         ('assigned','in_progress'), ('in_progress','blocked'), ('blocked','in_progress'),
         ('changes_requested','in_progress'), ('in_progress','submitted'), ('changes_requested','submitted'))) then
      raise exception 'transition % → % not allowed', v_task.status, p_status;
    end if;
  end if;

  if p_status = 'open' then
    update tasks set status = 'open', is_open_pool = true, assigned_to = null where id = p_task_id;
  else
    update tasks set status = p_status,
           blocked_reason = case when p_status = 'blocked' then p_note else null end,
           approval_stage = case when p_status = 'submitted' then 0 else approval_stage end
     where id = p_task_id;
  end if;
  select version into v_new from tasks where id = p_task_id;

  perform _task_log(v_task.company_id, p_task_id, 'status',
                    jsonb_build_object('from', v_task.status, 'to', p_status, 'note', p_note));
  if p_status = 'submitted' then
    -- notify everyone who can approve the first stage in this department
    insert into notifications (company_id, user_id, kind, title, body, task_id)
    select v_task.company_id, m.user_id, 'review', 'Work submitted for review', v_task.title, p_task_id
      from company_memberships m join company_roles r on r.id = m.role_id
     where m.company_id = v_task.company_id and m.status = 'active' and m.user_id <> v_uid
       and r.key = v_task.approval_chain[1]
       and (v_task.department_id is null or m.department_id = v_task.department_id);
  end if;
  return v_new;
end $$;

-- 9.8 Multi-stage approval. Approver rank must meet the stage role; no self-approval.
create or replace function public.decide_approval(p_task_id uuid, p_decision text, p_note text default null)
returns text language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := (select auth.uid());
  v_task tasks;
  v_stage_role text;
  v_needed int;
  v_last boolean;
begin
  if p_decision not in ('approved','changes_requested') then raise exception 'bad decision'; end if;
  select * into v_task from tasks where id = p_task_id for update;
  if v_task.id is null or not has_permission(v_task.company_id, 'task.approve') then raise exception 'forbidden'; end if;
  if v_task.status <> 'submitted' then raise exception 'task is not awaiting review'; end if;
  if v_task.requires_independent_approval and v_task.assigned_to = v_uid then
    raise exception 'you cannot approve your own work';
  end if;
  if p_decision = 'changes_requested' and coalesce(trim(p_note), '') = '' then
    raise exception 'explain what needs to change';
  end if;

  v_stage_role := v_task.approval_chain[v_task.approval_stage + 1];
  select rank into v_needed from company_roles where company_id = v_task.company_id and key = v_stage_role;
  if my_rank(v_task.company_id) < coalesce(v_needed, 100) then
    raise exception 'this stage needs a % or higher', v_stage_role;
  end if;

  insert into task_approvals (company_id, task_id, stage, stage_role, approver_id, decision, note)
  values (v_task.company_id, p_task_id, v_task.approval_stage, v_stage_role, v_uid, p_decision, p_note);

  if p_decision = 'changes_requested' then
    update tasks set status = 'changes_requested', approval_stage = 0 where id = p_task_id;
    perform _notify(v_task.company_id, v_task.assigned_to, 'changes', 'Changes requested', v_task.title, p_task_id);
    perform _task_log(v_task.company_id, p_task_id, 'changes_requested', jsonb_build_object('note', p_note));
    perform _audit(v_task.company_id, 'task.changes_requested', 'task', p_task_id::text);
    return 'CHANGES_REQUESTED';
  end if;

  v_last := v_task.approval_stage + 1 >= coalesce(array_length(v_task.approval_chain, 1), 1);
  if v_last then
    update tasks set status = 'approved', approval_stage = approval_stage + 1 where id = p_task_id;
    perform _notify(v_task.company_id, v_task.assigned_to, 'approved', 'Work approved', v_task.title, p_task_id);
  else
    update tasks set approval_stage = approval_stage + 1 where id = p_task_id;
  end if;
  perform _task_log(v_task.company_id, p_task_id, 'approved',
                    jsonb_build_object('stage', v_task.approval_stage, 'role', v_stage_role, 'final', v_last));
  perform _audit(v_task.company_id, 'task.approve', 'task', p_task_id::text,
                 jsonb_build_object('stage', v_task.approval_stage, 'final', v_last));
  return case when v_last then 'APPROVED' else 'STAGE_APPROVED' end;
end $$;

-- 9.9 Checklist tick (never approves the task)
create or replace function public.toggle_checklist(p_item uuid, p_done boolean)
returns void language plpgsql security definer set search_path = public as $$
declare v_item task_checklists; v_task tasks;
begin
  select * into v_item from task_checklists where id = p_item;
  select * into v_task from tasks where id = v_item.task_id;
  if v_task.id is null or not (v_task.assigned_to = (select auth.uid()) or has_permission(v_task.company_id, 'task.edit')) then
    raise exception 'forbidden';
  end if;
  update task_checklists set is_done = p_done where id = p_item;
end $$;

-- 9.10 Parent progress: share of finished subtasks (effort-weighted when estimates exist)
create or replace function public.task_progress(p_task_id uuid)
returns numeric language sql stable security invoker set search_path = public as $$
  select case when count(*) = 0 then null
    when bool_and(estimated_effort is not null) then
      round(100.0 * sum(estimated_effort) filter (where status in ('approved','published'))
                  / nullif(sum(estimated_effort), 0), 1)
    else round(100.0 * count(*) filter (where status in ('approved','published')) / count(*), 1) end
  from tasks where parent_task_id = p_task_id;
$$;

-- 9.11 Workspaces for the signed-in user (company switcher)
create or replace function public.my_workspaces()
returns table (company_id uuid, company_name text, slug text, industry text, brand_color text,
               company_status text, role_key text, role_name text, role_rank int,
               department_id uuid, permissions text[], modules text[])
language sql stable security definer set search_path = public as $$
  select c.id, c.name, c.slug, c.industry, c.brand_color, c.status, r.key, r.name, r.rank, m.department_id,
         coalesce((select array_agg(permission order by permission) from role_permissions where role_id = r.id), '{}'),
         coalesce((select array_agg(module order by module) from company_modules
                    where company_id = c.id and enabled), '{}')
  from company_memberships m
  join companies c on c.id = m.company_id
  join company_roles r on r.id = m.role_id
  where m.user_id = (select auth.uid()) and m.status = 'active'
  order by c.name;
$$;

-- 9.12 Dashboard summary (one small query; RLS-equivalent filtering applied)
create or replace function public.dashboard_summary(p_company uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare v_uid uuid := (select auth.uid()); v_all boolean;
begin
  if not is_member(p_company) then raise exception 'forbidden'; end if;
  v_all := has_permission(p_company, 'report.company');
  return (
    select jsonb_build_object(
      'scope',            case when v_all then 'company' else 'personal' end,
      'active',           count(*) filter (where status in ('assigned','in_progress','blocked','submitted','changes_requested')),
      'overdue',          count(*) filter (where due_at < now() and status not in ('approved','published','cancelled')),
      'awaiting_review',  count(*) filter (where status = 'submitted'),
      'blocked',          count(*) filter (where status = 'blocked'),
      'open_pool',        count(*) filter (where status = 'open' and is_open_pool),
      'done_today',       count(*) filter (where status in ('approved','published') and updated_at::date = now()::date),
      'done_week',        count(*) filter (where status in ('approved','published') and updated_at > now() - interval '7 days'))
    from tasks
    where company_id = p_company and parent_task_id is null
      and (v_all or assigned_to = v_uid or created_by = v_uid));
end $$;

-- 9.13 Platform console (platform owners only). Sees companies, never their tasks.
create or replace function public.platform_set_company_status(p_company uuid, p_status text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not is_platform_admin() then raise exception 'forbidden'; end if;
  update companies set status = p_status where id = p_company;
  perform _audit(p_company, 'platform.company_status', 'company', p_company::text, jsonb_build_object('status', p_status));
end $$;

create or replace function public.platform_overview()
returns table (company_id uuid, name text, industry text, status text, plan text, members bigint, tasks bigint, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not is_platform_admin() then raise exception 'forbidden'; end if;
  return query
    select c.id, c.name, c.industry, c.status, c.plan,
           (select count(*) from company_memberships m where m.company_id = c.id and m.status = 'active'),
           (select count(*) from tasks t where t.company_id = c.id),   -- counts only, no content
           c.created_at
    from companies c order by c.created_at desc;
end $$;

-- Company owners grant time-limited support access to a platform admin
create or replace function public.grant_support_access(p_company uuid, p_platform_user uuid, p_hours int, p_reason text)
returns uuid language plpgsql security definer set search_path = public as $$
declare v_id uuid;
begin
  if not has_permission(p_company, 'company.manage') then raise exception 'forbidden'; end if;
  if p_hours not between 1 and 72 then raise exception 'support access must be 1–72 hours'; end if;
  insert into support_access_grants (company_id, platform_user_id, granted_by, reason, expires_at)
  values (p_company, p_platform_user, (select auth.uid()), p_reason, now() + make_interval(hours => p_hours))
  returning id into v_id;
  perform _audit(p_company, 'support.grant', 'support_access', v_id::text,
                 jsonb_build_object('hours', p_hours, 'reason', p_reason));
  return v_id;
end $$;

-- 9.14 Editorial priority score (configurable weights per company)
create or replace function public.editorial_score(p_submission uuid)
returns numeric language sql stable security invoker set search_path = public as $$
  select round(
      coalesce(s.score_f,0) * (w->>'F')::numeric + coalesce(s.score_i,0) * (w->>'I')::numeric
    + coalesce(s.score_u,0) * (w->>'U')::numeric + coalesce(s.score_a,0) * (w->>'A')::numeric
    + coalesce(s.score_r,0) * (w->>'R')::numeric + coalesce(s.score_v,0) * (w->>'V')::numeric, 2)
  from coverage_submissions s
  cross join lateral (select editorial_weights as w from company_settings where company_id = s.company_id) cs
  where s.id = p_submission;
$$;

-- =====================================================================
-- 10. Row Level Security — deny by default, every table
-- =====================================================================
do $$
declare t text;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon, authenticated', t);
  end loop;
end $$;

-- profiles: you, and people who share a company with you
create policy profiles_select on public.profiles for select to authenticated using (
  id = (select auth.uid()) or exists (
    select 1 from company_memberships a join company_memberships b on a.company_id = b.company_id
    where a.user_id = (select auth.uid()) and a.status = 'active' and b.user_id = profiles.id));
create policy profiles_update on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
grant select on public.profiles to authenticated;
grant update (full_name, avatar_hue) on public.profiles to authenticated;

-- platform_admins: only see your own row
create policy platform_admins_self on public.platform_admins for select to authenticated
  using (user_id = (select auth.uid()));
grant select on public.platform_admins to authenticated;

-- companies: members (any status company so the app can show "suspended"), platform admins
create policy companies_select on public.companies for select to authenticated using (
  is_platform_admin() or exists (select 1 from company_memberships m where m.company_id = companies.id
                                 and m.user_id = (select auth.uid()) and m.status = 'active'));
create policy companies_update on public.companies for update to authenticated
  using (has_permission(id, 'company.manage')) with check (has_permission(id, 'company.manage'));
grant select on public.companies to authenticated;
grant update (name, brand_color, logo_url) on public.companies to authenticated;

create policy usage_platform on public.tenant_usage_daily for select to authenticated using (is_platform_admin());
grant select on public.tenant_usage_daily to authenticated;

create policy settings_select on public.company_settings for select to authenticated using (is_member(company_id));
create policy settings_update on public.company_settings for update to authenticated
  using (has_permission(company_id, 'company.manage')) with check (has_permission(company_id, 'company.manage'));
grant select on public.company_settings to authenticated;
grant update (member_visibility, mfa_required_rank, claim_expiry_hours, approval_reminder_hours,
              default_approval_chain, editorial_weights, assignment_weights) on public.company_settings to authenticated;

create policy modules_select on public.company_modules for select to authenticated using (is_member(company_id));
create policy modules_update on public.company_modules for update to authenticated
  using (has_permission(company_id, 'module.manage')) with check (has_permission(company_id, 'module.manage'));
grant select on public.company_modules to authenticated;
grant update (enabled) on public.company_modules to authenticated;

create policy roles_select on public.company_roles for select to authenticated using (is_member(company_id));
grant select on public.company_roles to authenticated;
create policy role_perms_select on public.role_permissions for select to authenticated using (is_member(company_id));
grant select on public.role_permissions to authenticated;

create policy depts_select on public.company_departments for select to authenticated using (is_member(company_id));
create policy depts_insert on public.company_departments for insert to authenticated
  with check (has_permission(company_id, 'department.manage'));
create policy depts_update on public.company_departments for update to authenticated
  using (has_permission(company_id, 'department.manage')) with check (has_permission(company_id, 'department.manage'));
create policy depts_delete on public.company_departments for delete to authenticated
  using (has_permission(company_id, 'department.manage'));
grant select, insert, delete on public.company_departments to authenticated;
grant update (name, head_user_id) on public.company_departments to authenticated;

-- memberships: visible to co-members; role/status changes only via functions
create policy memberships_select on public.company_memberships for select to authenticated
  using (user_id = (select auth.uid()) or is_member(company_id));
create policy memberships_update_self on public.company_memberships for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy memberships_update_mgr on public.company_memberships for update to authenticated
  using (has_permission(company_id, 'member.manage')) with check (has_permission(company_id, 'member.manage'));
grant select on public.company_memberships to authenticated;
-- members may only change their availability/skills/title; role_id & status are NOT grantable
grant update (availability, skills, title) on public.company_memberships to authenticated;
-- department moves go through set_member_department (prevents self-moving into another department)

create policy invitations_select on public.company_invitations for select to authenticated
  using (has_permission(company_id, 'member.invite'));
create policy invitations_revoke on public.company_invitations for update to authenticated
  using (has_permission(company_id, 'member.invite')) with check (status = 'revoked');
grant select (id, company_id, email, role_id, department_id, status, invited_by, expires_at, created_at)
  on public.company_invitations to authenticated;
grant update (status) on public.company_invitations to authenticated;

create policy support_select on public.support_access_grants for select to authenticated
  using (has_permission(company_id, 'company.manage') or platform_user_id = (select auth.uid()));
create policy support_revoke on public.support_access_grants for update to authenticated
  using (has_permission(company_id, 'company.manage')) with check (has_permission(company_id, 'company.manage'));
grant select on public.support_access_grants to authenticated;
grant update (revoked_at) on public.support_access_grants to authenticated;

-- projects
create policy projects_select on public.projects for select to authenticated
  using (is_member(company_id) or has_support_access(company_id));
create policy projects_insert on public.projects for insert to authenticated
  with check (has_permission(company_id, 'task.create') and created_by = (select auth.uid()));
create policy projects_update on public.projects for update to authenticated
  using (has_permission(company_id, 'task.edit')) with check (has_permission(company_id, 'task.edit'));
grant select, insert on public.projects to authenticated;
grant update (name, description, status) on public.projects to authenticated;

-- tasks: visibility helper; inserts need task.create (or task.request → inbox only)
create policy tasks_select on public.tasks for select to authenticated
  using (can_view_task(company_id, department_id, assigned_to, created_by, is_open_pool, status));
create policy tasks_insert on public.tasks for insert to authenticated with check (
  created_by = (select auth.uid()) and (
     has_permission(company_id, 'task.create')
     or (has_permission(company_id, 'task.request') and status = 'inbox' and assigned_to is null and not is_open_pool)));
create policy tasks_update on public.tasks for update to authenticated
  using (has_permission(company_id, 'task.edit') or created_by = (select auth.uid()))
  with check (is_member(company_id));
create policy tasks_delete on public.tasks for delete to authenticated
  using (has_permission(company_id, 'company.manage'));
grant select, delete on public.tasks to authenticated;
grant insert (id, company_id, project_id, parent_task_id, department_id, title, description, status, priority,
              created_by, assigned_to, is_open_pool, eligible_skill, start_date, due_at, estimated_effort,
              approval_chain, requires_independent_approval) on public.tasks to authenticated;
-- status/assignee/approval columns are NOT directly updatable: use the RPC functions
grant update (title, description, priority, start_date, due_at, estimated_effort, department_id, project_id, eligible_skill)
  on public.tasks to authenticated;

create policy checklists_select on public.task_checklists for select to authenticated using (
  exists (select 1 from tasks t where t.id = task_id));          -- inherits task visibility through RLS
create policy checklists_insert on public.task_checklists for insert to authenticated with check (
  has_permission(company_id, 'task.edit') and exists (select 1 from tasks t where t.id = task_id and t.company_id = task_checklists.company_id));
grant select, insert on public.task_checklists to authenticated;

create policy deps_select on public.task_dependencies for select to authenticated using (is_member(company_id));
create policy deps_insert on public.task_dependencies for insert to authenticated with check (has_permission(company_id, 'task.edit'));
grant select, insert, delete on public.task_dependencies to authenticated;
create policy deps_delete on public.task_dependencies for delete to authenticated using (has_permission(company_id, 'task.edit'));

create policy claims_select    on public.task_claims    for select to authenticated using (exists (select 1 from tasks t where t.id = task_id));
create policy approvals_select on public.task_approvals for select to authenticated using (exists (select 1 from tasks t where t.id = task_id));
create policy activity_select  on public.task_activity  for select to authenticated using (exists (select 1 from tasks t where t.id = task_id));
grant select on public.task_claims, public.task_approvals, public.task_activity to authenticated;

create policy comments_select on public.task_comments for select to authenticated using (exists (select 1 from tasks t where t.id = task_id));
create policy comments_insert on public.task_comments for insert to authenticated with check (
  author_id = (select auth.uid()) and exists (select 1 from tasks t where t.id = task_id and t.company_id = task_comments.company_id));
grant select, insert on public.task_comments to authenticated;

-- chat
create policy channels_select on public.chat_channels for select to authenticated using (can_access_channel(id));
create policy channels_insert on public.chat_channels for insert to authenticated with check (
  created_by = (select auth.uid()) and is_member(company_id)
  and (kind <> 'announcement' or has_permission(company_id, 'chat.announce')));
grant select, insert on public.chat_channels to authenticated;

create policy chat_members_select on public.chat_members for select to authenticated using (can_access_channel(channel_id));
create policy chat_members_insert on public.chat_members for insert to authenticated with check (
  is_member(company_id) and can_access_channel(channel_id)
  and exists (select 1 from company_memberships m where m.company_id = chat_members.company_id
              and m.user_id = chat_members.user_id and m.status = 'active'));
create policy chat_members_update on public.chat_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
grant select, insert on public.chat_members to authenticated;
grant update (last_read_at) on public.chat_members to authenticated;

create policy messages_select on public.messages for select to authenticated using (can_access_channel(channel_id));
create policy messages_insert on public.messages for insert to authenticated with check (
  sender_id = (select auth.uid()) and can_access_channel(channel_id)
  and exists (select 1 from chat_channels c where c.id = channel_id and c.company_id = messages.company_id
              and (c.kind <> 'announcement' or has_permission(c.company_id, 'chat.announce'))));
create policy messages_update on public.messages for update to authenticated
  using (sender_id = (select auth.uid())) with check (sender_id = (select auth.uid()));
grant select, insert on public.messages to authenticated;
grant update (body, edited_at) on public.messages to authenticated;

create policy notifications_select on public.notifications for select to authenticated using (user_id = (select auth.uid()));
create policy notifications_update on public.notifications for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
grant select on public.notifications to authenticated;
grant update (read_at) on public.notifications to authenticated;

create policy device_tokens_own on public.device_tokens for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
grant select, insert, update, delete on public.device_tokens to authenticated;

create policy audit_select on public.audit_logs for select to authenticated using (has_permission(company_id, 'audit.view'));
grant select on public.audit_logs to authenticated;

-- coverage (only when module enabled)
create or replace function public.module_enabled(cid uuid, mod text)
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce((select enabled from company_modules where company_id = cid and module = mod), false);
$$;
create policy coverage_events_select on public.coverage_events for select to authenticated
  using (is_member(company_id) and module_enabled(company_id, 'coverage'));
create policy coverage_events_insert on public.coverage_events for insert to authenticated
  with check (has_permission(company_id, 'coverage.manage') and module_enabled(company_id, 'coverage')
              and created_by = (select auth.uid()));
create policy coverage_events_update on public.coverage_events for update to authenticated
  using (has_permission(company_id, 'coverage.manage')) with check (has_permission(company_id, 'coverage.manage'));
grant select, insert on public.coverage_events to authenticated;
grant update (name, sport, venue, event_date, importance, status) on public.coverage_events to authenticated;

create policy coverage_sub_select on public.coverage_submissions for select to authenticated
  using (is_member(company_id) and module_enabled(company_id, 'coverage'));
create policy coverage_sub_insert on public.coverage_submissions for insert to authenticated
  with check (has_permission(company_id, 'coverage.submit') and reporter_id = (select auth.uid())
              and module_enabled(company_id, 'coverage'));
create policy coverage_sub_update on public.coverage_submissions for update to authenticated
  using (has_permission(company_id, 'coverage.manage') or reporter_id = (select auth.uid()))
  with check (is_member(company_id));
grant select, insert on public.coverage_submissions to authenticated;
grant update (athlete, result, quotes, news_angle, media_links, verified, score_f, score_i, score_u, score_a,
              score_r, score_v, editorial_status, override_reason) on public.coverage_submissions to authenticated;

-- Functions: only authenticated users may call the public RPCs; internals are locked
revoke execute on all functions in schema public from public, anon;
grant execute on all functions in schema public to authenticated;
revoke execute on function public._audit(uuid,text,text,text,jsonb), public._notify(uuid,uuid,text,text,text,uuid),
                           public._task_log(uuid,uuid,text,jsonb) from authenticated;

-- =====================================================================
-- 11. Realtime (Supabase only): publish tables the app subscribes to
-- =====================================================================
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    alter publication supabase_realtime add table public.tasks, public.messages, public.notifications;
  end if;
end $$;
