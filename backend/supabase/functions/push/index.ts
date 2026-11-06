// DevNex push sender — Supabase Edge Function (Deno).
// Trigger: Database Webhook on INSERT into public.notifications.
// Sends the notification to every registered device of that user through FCM HTTP v1.
// Secrets (set with `supabase secrets set ...`, never in the app):
//   FCM_PROJECT_ID, FCM_CLIENT_EMAIL, FCM_PRIVATE_KEY   (from a Firebase service account)
//   WEBHOOK_SECRET                                      (shared with the database webhook header)
import { createClient } from "jsr:@supabase/supabase-js@2";

const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

function b64url(data: ArrayBuffer | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = ""; bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

let cached: { token: string; exp: number } | null = null;
async function accessToken(): Promise<string> {
  if (cached && cached.exp > Date.now() + 60_000) return cached.token;
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: Deno.env.get("FCM_CLIENT_EMAIL"), scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  }));
  const pem = Deno.env.get("FCM_PRIVATE_KEY")!.replace(/\\n/g, "\n")
    .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(/\s/g, "");
  const key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${header}.${claim}.${b64url(sig)}`,
  });
  const json = await res.json();
  cached = { token: json.access_token, exp: Date.now() + json.expires_in * 1000 };
  return cached.token;
}

Deno.serve(async (req) => {
  if (req.headers.get("x-webhook-secret") !== Deno.env.get("WEBHOOK_SECRET")) return new Response("forbidden", { status: 403 });
  const { record } = await req.json();            // the inserted notifications row
  const { data: tokens } = await supabase.from("device_tokens").select("token").eq("user_id", record.user_id);
  if (!tokens?.length) return new Response("no devices");

  const bearer = await accessToken();
  const project = Deno.env.get("FCM_PROJECT_ID");
  const results = await Promise.all(tokens.map(async ({ token }) => {
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${project}/messages:send`, {
      method: "POST", headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
      body: JSON.stringify({ message: {
        token, notification: { title: record.title, body: record.body },
        data: { task_id: record.task_id ?? "", company_id: record.company_id, kind: record.kind },
        android: { priority: record.kind === "assignment" || record.kind === "review" ? "HIGH" : "NORMAL" },
      } }),
    });
    // Remove tokens FCM says are dead, so we never retry them
    if (r.status === 404 || r.status === 400) await supabase.from("device_tokens").delete().eq("token", token);
    return r.status;
  }));
  return Response.json({ sent: results.filter((s) => s === 200).length, total: results.length });
});
