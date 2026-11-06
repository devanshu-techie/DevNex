-- DevNex RLS & business-rule test suite.
-- Two companies, six users. Every check raises on failure, so a clean
-- run that prints "ALL TESTS PASSED" means every rule held.
\set ON_ERROR_STOP on
set client_min_messages = warning;
\o /dev/null

-- ---------- users ----------
insert into auth.users (id, email, raw_user_meta_data) values
 ('00000000-0000-0000-0000-00000000000a', 'asha@alpha.test',  '{"full_name":"Asha (Alpha owner)"}'),
 ('00000000-0000-0000-0000-00000000000b', 'ravi@alpha.test',  '{"full_name":"Ravi (Alpha member)"}'),
 ('00000000-0000-0000-0000-00000000000c', 'meera@alpha.test', '{"full_name":"Meera (Alpha member)"}'),
 ('00000000-0000-0000-0000-00000000000d', 'li@alpha.test',    '{"full_name":"Li (Alpha lead)"}'),
 ('00000000-0000-0000-0000-0000000000b0', 'bob@beta.test',    '{"full_name":"Bob (Beta owner)"}'),
 ('00000000-0000-0000-0000-0000000000ff', 'dev@devnex.test',  '{"full_name":"Devanshu (platform)"}');
insert into public.platform_admins (user_id) values ('00000000-0000-0000-0000-0000000000ff');

create or replace function pg_temp.act_as(u text) returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claim.sub', u, false);
  execute 'set role authenticated';
end $$;
create or replace function pg_temp.check(ok boolean, msg text) returns void language plpgsql as $$
begin
  if not coalesce(ok, false) then raise exception 'TEST FAILED: %', msg; end if;
  raise notice 'ok  - %', msg;
end $$;
set client_min_messages = notice;

-- ---------- 1. company creation ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
select public.create_company('Alpha Sports Media', 'alpha-sports', 'newsroom') as alpha \gset
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
select public.create_company('Beta Agency', 'beta-agency', 'agency') as beta \gset
select set_config('t.alpha', :'alpha', false), set_config('t.beta', :'beta', false) \g /dev/null

-- ---------- 2. invitations ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
select id as reporting from public.company_departments where company_id = :'alpha' and name = 'Reporting' \gset
select id as video     from public.company_departments where company_id = :'alpha' and name = 'Video' \gset
select public.create_invitation(:'alpha', 'ravi@alpha.test',  'member', :'reporting') as tok_ravi \gset
select public.create_invitation(:'alpha', 'meera@alpha.test', 'member', :'reporting') as tok_meera \gset
select public.create_invitation(:'alpha', 'li@alpha.test',    'lead',   :'reporting') as tok_li \gset
select set_config('t.ravi', :'tok_ravi', false) \g /dev/null

select pg_temp.act_as('00000000-0000-0000-0000-00000000000c');   -- Meera tries Ravi's token
do $$ begin
  perform public.accept_invitation(current_setting('t.ravi'));
  raise exception 'TEST FAILED: wrong-email invitation accepted';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - invitation bound to its email (%).', sqlerrm;
end $$;
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select public.accept_invitation(:'tok_ravi') is not null as ravi_joined \gset
select pg_temp.act_as('00000000-0000-0000-0000-00000000000c');
select public.accept_invitation(:'tok_meera') is not null as meera_joined \gset
select pg_temp.act_as('00000000-0000-0000-0000-00000000000d');
select public.accept_invitation(:'tok_li') is not null as li_joined \gset
select pg_temp.check(:'ravi_joined' and :'meera_joined' and :'li_joined', 'three employees joined Alpha via invitations');

select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
do $$ begin
  perform public.accept_invitation(current_setting('t.ravi'));
  raise exception 'TEST FAILED: invitation reused';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - invitation cannot be reused';
end $$;

-- ---------- 3. tenant isolation ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
insert into public.tasks (company_id, title, created_by, department_id, priority, status, is_open_pool)
values (:'alpha', 'Cover National Athletics Event', '00000000-0000-0000-0000-00000000000a', :'reporting', 'high', 'open', true);
select id as pool_task from public.tasks where title = 'Cover National Athletics Event' \gset
select set_config('t.pool', :'pool_task', false) \g /dev/null

select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
insert into public.tasks (company_id, title, created_by) values (:'beta', 'Beta client pitch', '00000000-0000-0000-0000-0000000000b0');
select pg_temp.check((select count(*) from public.tasks where company_id = :'alpha') = 0, 'Beta owner sees zero Alpha tasks');
select pg_temp.check((select count(*) from public.company_memberships where company_id = :'alpha') = 0, 'Beta owner sees zero Alpha members');
select pg_temp.check((select count(*) from public.profiles where email like '%alpha.test') = 0, 'Beta owner sees zero Alpha profiles');
select pg_temp.check((select count(*) from public.messages) = 0 and (select count(*) from public.chat_channels where company_id = :'alpha') = 0,
                     'Beta owner sees zero Alpha channels');
do $$ declare v_alpha uuid := current_setting('t.alpha')::uuid; begin
  insert into public.tasks (company_id, title, created_by) values (v_alpha, 'Injected', '00000000-0000-0000-0000-0000000000b0');
  raise exception 'TEST FAILED: cross-tenant insert allowed';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - Beta owner cannot insert into Alpha (%).', sqlerrm;
end $$;
select pg_temp.check(public.claim_task(:'pool_task') = 'FORBIDDEN', 'Beta owner cannot claim an Alpha task');
select pg_temp.check((select public.dashboard_summary(:'beta')->>'active') is not null, 'Beta dashboard works for Beta');

-- ---------- 4. platform owner separation ----------
select pg_temp.act_as('00000000-0000-0000-0000-0000000000ff');
select pg_temp.check((select count(*) from public.companies) = 2, 'platform owner sees company list');
select pg_temp.check((select count(*) from public.tasks) = 0, 'platform owner cannot read company tasks by default');
select pg_temp.check((select tasks from public.platform_overview() where company_id = :'alpha') = 1, 'platform overview shows counts only');

-- ---------- 5. role safety ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
do $$ begin
  update public.company_memberships set role_id = (select id from public.company_roles where key='owner' limit 1)
   where user_id = '00000000-0000-0000-0000-00000000000b';
  raise exception 'TEST FAILED: member promoted self';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - member cannot change own role (%).', sqlerrm;
end $$;
do $$ begin
  update public.company_memberships set department_id = null where user_id = '00000000-0000-0000-0000-00000000000b';
  raise exception 'TEST FAILED: member changed own department';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - member cannot move self to another department';
end $$;
do $$ begin
  update public.tasks set status = 'approved' where true;
  raise exception 'TEST FAILED: direct status update allowed';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - direct status update is blocked (%).', sqlerrm;
end $$;

-- ---------- 6. visibility: members see dept + own + open pool ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
insert into public.tasks (company_id, title, created_by, department_id, status)
values (:'alpha', 'Video-only internal task', '00000000-0000-0000-0000-00000000000a', :'video', 'inbox');
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select pg_temp.check((select count(*) from public.tasks where title = 'Video-only internal task') = 0, 'Reporting member cannot see Video dept task');
select pg_temp.check((select count(*) from public.tasks where id = :'pool_task') = 1, 'member sees open-pool task in own dept');

-- ---------- 7. concurrent claim: exactly one winner ----------
-- (run separately by run_tests.sh with two real sessions; here sequential sanity)
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select public.claim_task(:'pool_task') as r1 \gset
select pg_temp.act_as('00000000-0000-0000-0000-00000000000c');
select public.claim_task(:'pool_task') as r2 \gset
select pg_temp.check(:'r1' = 'CLAIM_SUCCESS' and :'r2' = 'CLAIM_FAILED', 'second claim fails after first succeeds');

-- ---------- 8. lifecycle + approvals ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select version as v from public.tasks where id = :'pool_task' \gset
select public.set_task_status(:'pool_task', 'in_progress', :v) as v \gset
do $$ begin
  perform public.set_task_status(current_setting('t.pool')::uuid, 'in_progress', 1);
  raise exception 'TEST FAILED: stale version accepted';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - stale version rejected (%).', sqlerrm;
end $$;
select public.set_task_status(:'pool_task', 'submitted', :v) as v \gset

do $$ begin
  perform public.decide_approval(current_setting('t.pool')::uuid, 'approved', null);
  raise exception 'TEST FAILED: member approved';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - member cannot approve (%).', sqlerrm;
end $$;

select pg_temp.act_as('00000000-0000-0000-0000-00000000000d');   -- Li, team lead
select pg_temp.check(public.decide_approval(:'pool_task', 'approved', 'Looks good') = 'STAGE_APPROVED', 'team lead approves stage 1');
do $$ begin
  perform public.decide_approval(current_setting('t.pool')::uuid, 'approved', null);
  raise exception 'TEST FAILED: lead approved head stage';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - team lead cannot approve the head stage (%).', sqlerrm;
end $$;
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
select pg_temp.check(public.decide_approval(:'pool_task', 'approved', null) = 'APPROVED', 'owner approves final stage');
select pg_temp.check((select status from public.tasks where id = :'pool_task') = 'approved', 'task is approved');
select pg_temp.check((select count(*) from public.task_activity where task_id = :'pool_task') >= 5, 'activity history recorded');
select pg_temp.check((select count(*) from public.audit_logs where entity_id = :'pool_task' and action like 'task.%') >= 3, 'audit trail recorded');

-- self-approval block
insert into public.tasks (company_id, title, created_by, assigned_to, status)
values (:'alpha', 'Owner own work', '00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-00000000000a', 'assigned');
select id as own_task, version as v from public.tasks where title = 'Owner own work' \gset
select public.set_task_status(:'own_task', 'in_progress', :v) as v \gset
select public.set_task_status(:'own_task', 'submitted', :v) as v \gset
do $$ begin
  perform public.decide_approval((select id from public.tasks where title='Owner own work'), 'approved', null);
  raise exception 'TEST FAILED: self approval';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - self-approval blocked (%).', sqlerrm;
end $$;

-- ---------- 9. notifications go to the right people ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select pg_temp.check((select count(*) from public.notifications where kind = 'approved') = 1, 'Ravi notified of approval');
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
select pg_temp.check((select count(*) from public.notifications) = 0, 'Beta owner gets no Alpha notifications');

-- ---------- 10. chat ----------
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select id as general from public.chat_channels where company_id = :'alpha' and name = 'general' \gset
insert into public.messages (id, company_id, channel_id, sender_id, body)
values ('11111111-1111-1111-1111-111111111111', :'alpha', :'general', '00000000-0000-0000-0000-00000000000b', 'Results are in!');
do $$ begin
  insert into public.messages (id, company_id, channel_id, sender_id, body)
  values ('11111111-1111-1111-1111-111111111111', current_setting('t.alpha')::uuid,
          (select id from public.chat_channels where name='general' and company_id = current_setting('t.alpha')::uuid),
          '00000000-0000-0000-0000-00000000000b', 'Results are in!');
  raise exception 'TEST FAILED: duplicate message';
exception when unique_violation then raise notice 'ok  - retry with same client UUID cannot duplicate a message';
end $$;
select id as ann from public.chat_channels where company_id = :'alpha' and name = 'announcements' \gset
do $$ begin
  insert into public.messages (id, company_id, channel_id, sender_id, body)
  values (gen_random_uuid(), current_setting('t.alpha')::uuid,
          (select id from public.chat_channels where name='announcements' and company_id = current_setting('t.alpha')::uuid),
          '00000000-0000-0000-0000-00000000000b', 'fake announcement');
  raise exception 'TEST FAILED: member posted announcement';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - members cannot post announcements';
end $$;
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
select pg_temp.check((select count(*) from public.messages) = 0, 'Beta owner cannot read Alpha messages');

-- ---------- 11. module gating ----------
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
do $$ begin
  insert into public.coverage_events (company_id, name, event_date, created_by)
  values (current_setting('t.beta')::uuid, 'x', current_date, '00000000-0000-0000-0000-0000000000b0');
  raise exception 'TEST FAILED: agency used coverage module';
exception when others then
  if sqlerrm like 'TEST FAILED%' then raise; end if;
  raise notice 'ok  - Coverage Desk disabled for the agency';
end $$;
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
insert into public.coverage_events (company_id, name, sport, event_date, created_by)
values (:'alpha', 'National Athletics Championship', 'Athletics', current_date, '00000000-0000-0000-0000-00000000000a');
select pg_temp.check((select count(*) from public.coverage_events) = 1, 'newsroom can register a coverage event');

-- ---------- 12. support access is time-limited & consented ----------
select public.grant_support_access(:'alpha', '00000000-0000-0000-0000-0000000000ff', 2, 'Debug sync issue') is not null as granted \gset
select pg_temp.act_as('00000000-0000-0000-0000-0000000000ff');
select pg_temp.check((select count(*) from public.tasks) > 0, 'platform owner reads Alpha tasks only after a support grant');
select pg_temp.check((select count(*) from public.tasks where company_id = :'beta') = 0, 'support grant does not leak Beta');

-- ---------- 13. suspension ----------
select public.platform_set_company_status(:'beta', 'suspended');
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
select pg_temp.check((select count(*) from public.tasks) = 0, 'suspended company loses data access');
select pg_temp.check((select company_status from public.my_workspaces()) = 'suspended', 'switcher shows the company as suspended');

reset role;
\o
\echo ALL TESTS PASSED
