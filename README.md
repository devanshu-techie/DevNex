# DevNex

**Every task. Every team. One nexus.** A multi-company operations platform for teams, tasks, approvals, coverage and chat. Powered by Devanshu.

This repository is the build of the *DeskFlow Refined Build Specification*, renamed **DevNex**. It covers Phase 1 (foundation) and Phase 2 (tasks), plus working first versions of Coverage Desk, messaging, insights, admin and the platform console.

```
devnex/
├── android/                       Kotlin · Jetpack Compose · Material 3 · Hilt · Room (SQLCipher) · WorkManager · Supabase
│   └── app/src/main/java/com/devanshu/devnex/
│       ├── core/        designsystem (theme, glass cards, aurora, pulse ring) · database · security · sync · network · notifications
│       ├── domain/      models + repository interfaces (the UI only talks to these)
│       ├── data/        demo/ (in-memory backend) · remote/ (Supabase) · repository/ (offline-first implementations)
│       ├── feature/     splash · auth · workspace · dashboard · tasks · coverage · chat · team · admin · settings
│       └── navigation/
└── backend/
    ├── supabase/migrations/0001_devnex_foundation.sql   tables, RLS, helper + business functions
    ├── supabase/functions/push/index.ts                 server-side FCM sender (Edge Function)
    ├── tests/run_tests.sh                               spins up PostgreSQL and runs the security suite
    └── TEST_RESULTS.txt                                 last run: 40 checks + concurrent-claim race, all passed
```

## Run the app (5 minutes, no backend needed)

1. Install **Android Studio Ladybug (2024.2) or newer**. It bundles JDK 17+.
2. *File → Open* → choose the `android/` folder. Let Gradle sync (first sync downloads dependencies).
3. Press **Run** on a phone or emulator running Android 8.0+.

With no Supabase keys, DevNex starts in **Demo mode**. The in-memory backend enforces the same rules as the database. Demo logins (password `devnex123` for all):

| Email | Who | Shows |
|---|---|---|
| `owner@devnex.app` | Devanshu | Platform console, company owner, MFA screen (code `123456`), two workspaces |
| `head@devnex.app` | Priya | Department head. Approves the final stage |
| `lead@devnex.app` | Arjun | Team lead. Approves stage 1 |
| `reporter@devnex.app` | Kabir | Member. Claims from the Open pool, submits work |
| `editor@devnex.app` | Neha | Member in the Video department |

**Screens:** animated DevNex splash → login / register → two-step code → workspace switcher, *create company* wizard, *join with invite code* → bottom nav: **Home** (live pulse ring, stat tiles, "needs your action", department progress, deadlines) · **My Work** (Mine / Open pool / To review / All, search, new task) · **Coverage** (events, reporter submissions, P-score ranking; becomes **Projects** for non-newsroom companies) · **Messages** (channels, live chat, offline queue) · **More** (team & workload, insights, company admin, platform console, settings, sign out). Task detail has the lifecycle pipeline, claim, start, block, submit, approve or request changes, smart assignment (S-score), checklist, subtasks, discussion and history.

## Connect the real backend (Supabase)

1. Create a project at supabase.com (Free is fine for development).
2. **SQL Editor** → paste `backend/supabase/migrations/0001_devnex_foundation.sql` → Run. Or `supabase db push` with the CLI.
3. **Authentication → Providers**: keep Email on. **Authentication → MFA**: enable TOTP.
4. Make yourself platform owner (SQL editor, after signing up once in the app):
   `insert into platform_admins (user_id) select id from auth.users where email = 'you@example.com';`
5. Copy `android/local.properties.example` to `android/local.properties` and fill in the **Project URL** and the **anon / publishable key** (Settings → API). Never use the `service_role` key in the app.
6. Rebuild. Settings in the app now shows *Supabase* instead of *Demo backend*.

### Push notifications (optional)
1. Create a Firebase project, add an Android app with package `com.devanshu.devnex`, download `google-services.json` into `android/app/`.
2. Add the plugin: in `android/build.gradle.kts` add `id("com.google.gms.google-services") version "4.4.2" apply false`, and in `app/build.gradle.kts` add `id("com.google.gms.google-services")`.
3. Deploy the sender: `supabase functions deploy push`, then set its secrets: `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY` (from a Firebase service account), `WEBHOOK_SECRET`.
4. Supabase **Database → Webhooks**: on `INSERT` into `notifications`, call the `push` function with header `x-webhook-secret`.
5. Still to wire: upload the device's FCM token into `device_tokens` after sign-in (the token is already saved locally by `DevNexMessagingService`). This is planned with Phase 5.

## What was tested, and how

| Area | How | Result |
|---|---|---|
| Database migration | Applied to a fresh PostgreSQL 16 with a Supabase `auth` stub | Applies cleanly |
| Tenant isolation, roles, approvals, chat, modules, support access, suspension | `backend/tests/10_rls_tests.sql`, 40 checks run as real `authenticated` users | **40/40 passed** |
| Two phones claim one task at the same moment | Two concurrent PostgreSQL sessions calling `claim_task` | **Exactly one winner** |
| Domain layer + Demo backend | Compiled with Kotlin 2.0.21 (the version the app pins) and run | **Compiles; 32/32 rule checks passed** |
| Compose UI, Room, Hilt, Supabase client | Written against pinned library versions | **Not compiled here** (no Android SDK in the build environment). First Android Studio build is the real check |

Re-run the database suite any time: `cd backend/tests && ./run_tests.sh` (needs PostgreSQL 16 binaries). Run the Kotlin rules test: `cd android && ./gradlew test`.

If the first Android Studio build reports an error, it is most likely in one of two places: a Supabase SDK call in `data/remote/SupabaseRemoteDataSource.kt` (MFA and realtime filter APIs change between versions), or a Material 3 parameter name. Paste the error and it is a quick fix.

## Security model (summary)

- `company_id` on every business row. **Row Level Security** on every table, deny by default, with indexed helper functions (`is_member`, `has_permission`, `can_view_task`).
- Status, assignee and approval columns are **not client-writable**. They change only through functions: `claim_task`, `set_task_status` (optimistic `version` check), `decide_approval` (rank per stage, no self-approval), `assign_task`, `release_task` (reason required).
- Members cannot change their own role, status or department. Invitations are hashed, single-use, bound to an email, and expire after 7 days.
- The platform owner sees counts only. Reading company content needs a **time-limited, audited support grant** from that company.
- Every claim, approval, assignment, membership change and deletion is written to `audit_logs`.
- On the device, the Room cache is encrypted with SQLCipher. Its key is wrapped by an Android Keystore AES-GCM key. Backups and device transfer are disabled, cleartext HTTP is refused, and sign-out wipes the cache.

## Decisions taken (change any of these)

- **Name and package:** DevNex, `com.devanshu.devnex`.
- **Visibility:** members see their own tasks, their department's tasks and the open pool. Heads and admins see everything (`company_settings.member_visibility`).
- **Default approval chain:** Team Lead → Department Head. It is configurable per task (Lead only, Head only, Head → Admin).
- **Coverage Desk:** on for newsroom companies, off for others. Admins can toggle it.
- **Offline:** reads work everywhere. Messages, checklist ticks and comments queue in an outbox. Claims and approvals need a connection.

## Next steps

- First build in Android Studio, then a pass on real devices (two phones claiming at once).
- Phase 3–4: publishing calendar and publication records. Phase 5: FCM token upload and mentions. Phase 6: summary tables, SLA escalation jobs, CSV export.
- Before onboarding outside companies: paid Supabase plan, encrypted off-site backups with a restore drill, privacy policy (DPDP Act 2023, confirm with a lawyer), trademark check for "DevNex".

---
POWERED BY DEVANSHU
