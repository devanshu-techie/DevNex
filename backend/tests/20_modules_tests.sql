-- Tests for migration 0002 (runs after 10_rls_tests.sql, reusing its users and companies).
\set ON_ERROR_STOP on
set client_min_messages = warning;
\o /dev/null
create or replace function pg_temp.act_as(u text) returns void language plpgsql as $$
begin perform set_config('request.jwt.claim.sub', u, false); execute 'set role authenticated'; end $$;
create or replace function pg_temp.check(ok boolean, msg text) returns void language plpgsql as $$
begin if not coalesce(ok, false) then raise exception 'TEST FAILED: %', msg; end if; raise notice 'ok  - %', msg; end $$;
set client_min_messages = notice;
reset role;
select id as alpha from public.companies where slug = 'alpha-sports' \gset
select id as beta  from public.companies where slug = 'beta-agency' \gset
select set_config('t.alpha', :'alpha', false), set_config('t.beta', :'beta', false);
update public.companies set status = 'active' where slug = 'beta-agency';   -- un-suspend from suite 10

-- publishing (Alpha = newsroom → module on)
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');   -- Ravi, member
insert into public.publishing_slots (company_id, title, platform, owner_id, scheduled_at, created_by)
values (:'alpha', 'Record holder interview reel', 'instagram', '00000000-0000-0000-0000-00000000000b', now() + interval '2 hours',
        '00000000-0000-0000-0000-00000000000b');
select id as slot from public.publishing_slots limit 1 \gset
do $$ begin
  perform public.publish_slot((select id from public.publishing_slots limit 1), 'not a link');
  raise exception 'TEST FAILED: publish without link';
exception when others then if sqlerrm like 'TEST FAILED%' then raise; end if; raise notice 'ok  - publishing needs a real link'; end $$;
select public.publish_slot(:'slot', 'https://instagram.com/p/abc');
select pg_temp.check((select status from public.publishing_slots where id = :'slot') = 'published', 'owner records publication');
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');   -- Bob (Beta)
select pg_temp.check((select count(*) from public.publishing_slots) = 0, 'Beta cannot see Alpha publishing calendar');

-- ideas
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
insert into public.ideas (company_id, title, impact, created_by) values (:'alpha', 'Podcast: coach roundtable', 'high', '00000000-0000-0000-0000-00000000000b');
select id as idea from public.ideas limit 1 \gset
do $$ begin
  perform public.set_idea_status((select id from public.ideas limit 1), 'approved');
  raise exception 'TEST FAILED: member approved idea';
exception when others then if sqlerrm like 'TEST FAILED%' then raise; end if; raise notice 'ok  - members cannot approve ideas'; end $$;
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');   -- Asha, owner
select public.set_idea_status(:'idea', 'approved');
select public.convert_idea_to_task(:'idea') as new_task \gset
select pg_temp.check((select priority from public.tasks where id = :'new_task') = 'high', 'idea converts into a high-priority task');
select pg_temp.check((select status from public.ideas where id = :'idea') = 'converted', 'idea marked converted');

-- ops log append-only
select pg_temp.act_as('00000000-0000-0000-0000-00000000000c');   -- Meera
insert into public.ops_log (company_id, category, body, author_id)
values (:'alpha', 'delay', 'Highlights delayed: missing camera B footage', '00000000-0000-0000-0000-00000000000c');
do $$ begin
  delete from public.ops_log;
  raise exception 'TEST FAILED: ops log deletable';
exception when others then if sqlerrm like 'TEST FAILED%' then raise; end if; raise notice 'ok  - operations log is append-only'; end $$;
select pg_temp.check((select count(*) from public.ops_log) = 1, 'members read the operations log');

-- direct messages
select public.open_direct_channel(:'alpha', '00000000-0000-0000-0000-00000000000b') as dm \gset
select pg_temp.check(public.open_direct_channel(:'alpha', '00000000-0000-0000-0000-00000000000b') = :'dm', 'opening a DM twice reuses it');
insert into public.messages (id, company_id, channel_id, sender_id, body)
values (gen_random_uuid(), :'alpha', :'dm', '00000000-0000-0000-0000-00000000000c', 'Private note to Ravi');
select pg_temp.act_as('00000000-0000-0000-0000-00000000000d');   -- Li, not in the DM
select pg_temp.check((select count(*) from public.messages where channel_id = :'dm') = 0, 'third colleague cannot read a DM');
select pg_temp.act_as('00000000-0000-0000-0000-00000000000b');
select pg_temp.check((select count(*) from public.messages where channel_id = :'dm') = 1, 'DM partner can read it');

-- company settings
do $$ begin
  perform public.update_company_profile(current_setting('t.alpha')::uuid, 'Hacked', '#000000', 'company', 0);
  raise exception 'TEST FAILED: member changed settings';
exception when others then if sqlerrm like 'TEST FAILED%' then raise; end if; raise notice 'ok  - members cannot change company settings'; end $$;
select pg_temp.act_as('00000000-0000-0000-0000-00000000000a');
select public.update_company_profile(:'alpha', 'Alpha Sports Media', '#0F766E', 'department', 90);
select pg_temp.check((select brand_color from public.companies where id = :'alpha') = '#0F766E', 'owner updates branding');

-- module gating: Beta's owner switches the ops log off, then writes are refused
select pg_temp.act_as('00000000-0000-0000-0000-0000000000b0');
update public.company_modules set enabled = false where company_id = :'beta' and module = 'ops_log';
do $$ begin
  insert into public.ops_log (company_id, category, body, author_id)
  values (current_setting('t.beta')::uuid, 'note', 'x', '00000000-0000-0000-0000-0000000000b0');
  raise exception 'TEST FAILED: module gate';
exception when others then if sqlerrm like 'TEST FAILED%' then raise; end if; raise notice 'ok  - disabled module rejects writes'; end $$;
reset role;
\o
\echo MODULE TESTS PASSED
