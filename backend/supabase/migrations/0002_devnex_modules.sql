-- =====================================================================
-- DevNex 0002 — optional modules: publishing calendar, ideas board,
-- operations log; direct messages; professional default brand colour.
-- =====================================================================

alter table public.companies alter column brand_color set default '#1D4ED8';

-- ---------------------------------------------------------------------
-- Publishing calendar + publication records (module: publishing)
-- ---------------------------------------------------------------------
create table public.publishing_slots (
  id            uuid primary key default gen_random_uuid(),
  company_id    uuid not null references public.companies(id) on delete cascade,
  title         text not null check (length(title) between 1 and 200),
  platform      text not null check (platform in ('instagram','facebook','youtube','x','linkedin','website','newsletter','other')),
  task_id       uuid references public.tasks(id) on delete set null,
  owner_id      uuid references auth.users(id),
  scheduled_at  timestamptz not null,
  status        text not null default 'draft' check (status in ('draft','scheduled','published','cancelled')),
  caption       text not null default '',
  published_url text,
  published_at  timestamptz,
  views         int,
  engagement    int,
  created_by    uuid not null references auth.users(id),
  version       int not null default 1,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  updated_by    uuid references auth.users(id)
);
create index idx_slots_company_time on public.publishing_slots (company_id, scheduled_at);

-- ---------------------------------------------------------------------
-- Ideas board (module: ideas)
-- ---------------------------------------------------------------------
create table public.ideas (
  id                uuid primary key default gen_random_uuid(),
  company_id        uuid not null references public.companies(id) on delete cascade,
  title             text not null check (length(title) between 1 and 200),
  description       text not null default '',
  impact            text not null default 'medium' check (impact in ('low','medium','high')),
  status            text not null default 'new' check (status in ('new','reviewing','approved','parked','converted')),
  proposed_owner    uuid references auth.users(id),
  review_date       date,
  converted_task_id uuid references public.tasks(id) on delete set null,
  created_by        uuid not null references auth.users(id),
  version           int not null default 1,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  updated_by        uuid references auth.users(id)
);
create index idx_ideas_company on public.ideas (company_id, status, created_at desc);

-- ---------------------------------------------------------------------
-- Operations log (module: ops_log) — append-only record of decisions
-- ---------------------------------------------------------------------
create table public.ops_log (
  id              bigint generated always as identity primary key,
  company_id      uuid not null references public.companies(id) on delete cascade,
  category        text not null check (category in ('decision','incident','delay','handover','note')),
  body            text not null check (length(body) between 1 and 4000),
  related_task_id uuid references public.tasks(id) on delete set null,
  author_id       uuid not null references auth.users(id),
  created_at      timestamptz not null default now()
);
create index idx_ops_log_company on public.ops_log (company_id, created_at desc);

do $$
declare t text;
begin
  foreach t in array array['publishing_slots','ideas'] loop
    execute format('create trigger trg_touch before update on public.%I for each row execute function public._touch()', t);
    execute format('create trigger trg_version before update on public.%I for each row execute function public._bump_version()', t);
  end loop;
  foreach t in array array['publishing_slots','ideas','ops_log'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon, authenticated', t);
  end loop;
end $$;

-- publishing: members of companies with the module; owners edit their slots, managers edit any
create policy slots_select on public.publishing_slots for select to authenticated
  using (is_member(company_id) and module_enabled(company_id, 'publishing'));
create policy slots_insert on public.publishing_slots for insert to authenticated
  with check (is_member(company_id) and module_enabled(company_id, 'publishing') and created_by = (select auth.uid()));
create policy slots_update on public.publishing_slots for update to authenticated
  using (is_member(company_id) and (owner_id = (select auth.uid()) or created_by = (select auth.uid()) or has_permission(company_id, 'task.assign')))
  with check (is_member(company_id));
grant select, insert on public.publishing_slots to authenticated;
grant update (title, platform, task_id, owner_id, scheduled_at, status, caption, published_url, published_at, views, engagement)
  on public.publishing_slots to authenticated;

-- ideas: anyone in the company proposes; managers change status
create policy ideas_select on public.ideas for select to authenticated
  using (is_member(company_id) and module_enabled(company_id, 'ideas'));
create policy ideas_insert on public.ideas for insert to authenticated
  with check (is_member(company_id) and module_enabled(company_id, 'ideas') and created_by = (select auth.uid()) and status = 'new');
create policy ideas_update on public.ideas for update to authenticated
  using (has_permission(company_id, 'task.assign') or created_by = (select auth.uid()))
  with check (is_member(company_id));
grant select, insert on public.ideas to authenticated;
grant update (title, description, impact, proposed_owner, review_date) on public.ideas to authenticated;

-- ops log: append-only, readable by members
create policy ops_select on public.ops_log for select to authenticated
  using (is_member(company_id) and module_enabled(company_id, 'ops_log'));
create policy ops_insert on public.ops_log for insert to authenticated
  with check (is_member(company_id) and module_enabled(company_id, 'ops_log') and author_id = (select auth.uid()));
grant select, insert on public.ops_log to authenticated;

-- ---------------------------------------------------------------------
-- Functions
-- ---------------------------------------------------------------------

-- Idea status changes are a manager decision
create or replace function public.set_idea_status(p_idea uuid, p_status text)
returns void language plpgsql security definer set search_path = public as $$
declare v ideas;
begin
  select * into v from ideas where id = p_idea;
  if v.id is null or not has_permission(v.company_id, 'task.assign') then raise exception 'forbidden'; end if;
  if p_status not in ('new','reviewing','approved','parked') then raise exception 'use convert_idea_to_task'; end if;
  update ideas set status = p_status where id = p_idea;
  perform _audit(v.company_id, 'idea.status', 'idea', p_idea::text, jsonb_build_object('status', p_status));
end $$;

-- Turn an idea into a real task in the inbox
create or replace function public.convert_idea_to_task(p_idea uuid)
returns uuid language plpgsql security definer set search_path = public as $$
declare v ideas; v_task uuid;
begin
  select * into v from ideas where id = p_idea for update;
  if v.id is null or not has_permission(v.company_id, 'task.create') then raise exception 'forbidden'; end if;
  if v.status = 'converted' then return v.converted_task_id; end if;
  insert into tasks (company_id, title, description, status, priority, created_by, assigned_to)
  values (v.company_id, v.title, v.description, case when v.proposed_owner is null then 'inbox' else 'assigned' end,
          case v.impact when 'high' then 'high' when 'low' then 'low' else 'normal' end, (select auth.uid()), v.proposed_owner)
  returning id into v_task;
  update ideas set status = 'converted', converted_task_id = v_task where id = p_idea;
  perform _audit(v.company_id, 'idea.convert', 'idea', p_idea::text, jsonb_build_object('task_id', v_task));
  return v_task;
end $$;

-- Record a publication: link + time, closes the slot
create or replace function public.publish_slot(p_slot uuid, p_url text)
returns void language plpgsql security definer set search_path = public as $$
declare v publishing_slots;
begin
  select * into v from publishing_slots where id = p_slot for update;
  if v.id is null or not is_member(v.company_id) then raise exception 'forbidden'; end if;
  if not (v.owner_id = (select auth.uid()) or has_permission(v.company_id, 'task.assign')) then raise exception 'forbidden'; end if;
  if coalesce(trim(p_url), '') !~ '^https?://' then raise exception 'add the published link (https://…)'; end if;
  update publishing_slots set status = 'published', published_url = p_url, published_at = now() where id = p_slot;
  perform _audit(v.company_id, 'publishing.published', 'publishing_slot', p_slot::text, jsonb_build_object('url', p_url));
end $$;

-- Open (or reuse) a private 1:1 conversation with a colleague
create or replace function public.open_direct_channel(p_company uuid, p_other uuid)
returns uuid language plpgsql security definer set search_path = public as $$
declare v_me uuid := (select auth.uid()); v_name text; v_id uuid;
begin
  if not is_member(p_company) then raise exception 'forbidden'; end if;
  if v_me = p_other then raise exception 'pick someone else'; end if;
  if not exists (select 1 from company_memberships where company_id = p_company and user_id = p_other and status = 'active') then
    raise exception 'that person is not in this company';
  end if;
  v_name := 'dm:' || least(v_me::text, p_other::text) || ':' || greatest(v_me::text, p_other::text);
  select id into v_id from chat_channels where company_id = p_company and name = v_name;
  if v_id is null then
    insert into chat_channels (company_id, name, kind, is_private, created_by)
    values (p_company, v_name, 'dm', true, v_me) returning id into v_id;
    insert into chat_members (company_id, channel_id, user_id) values (p_company, v_id, v_me), (p_company, v_id, p_other);
  end if;
  return v_id;
end $$;

-- Company settings in one call (owner/admin)
create or replace function public.update_company_profile(p_company uuid, p_name text, p_brand text, p_visibility text, p_mfa_rank int)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not has_permission(p_company, 'company.manage') then raise exception 'forbidden'; end if;
  if p_brand !~ '^#[0-9A-Fa-f]{6}$' then raise exception 'brand colour must look like #1D4ED8'; end if;
  update companies set name = p_name, brand_color = p_brand where id = p_company;
  update company_settings set member_visibility = p_visibility, mfa_required_rank = p_mfa_rank where company_id = p_company;
  perform _audit(p_company, 'company.settings', 'company', p_company::text,
                 jsonb_build_object('visibility', p_visibility, 'mfa_rank', p_mfa_rank));
end $$;

revoke execute on function public.set_idea_status(uuid,text), public.convert_idea_to_task(uuid), public.publish_slot(uuid,text),
  public.open_direct_channel(uuid,uuid), public.update_company_profile(uuid,text,text,text,int) from public, anon;
grant execute on function public.set_idea_status(uuid,text), public.convert_idea_to_task(uuid), public.publish_slot(uuid,text),
  public.open_direct_channel(uuid,uuid), public.update_company_profile(uuid,text,text,text,int) to authenticated;
