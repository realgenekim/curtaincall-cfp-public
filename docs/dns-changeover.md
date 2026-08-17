# DNS changeover — curtaincallcfp.com → the curtaincallcfp Cloud Run service

*Written 2026-08-11 02:40, the night-push milestone list. Split by who acts:
the laptop seat runs gcloud; Gene clicks Cloudflare and the OAuth console.*

## The shape

Cloud Run **domain mapping** + Cloudflare **DNS-only** records (gray cloud,
NOT proxied — Google terminates TLS and provisions the cert; a proxied
orange-cloud record breaks the cert provisioning handshake).

## Steps, in order

1. **[laptop] Verify domain ownership** (one-time, if not already):
   `gcloud domains verify curtaincallcfp.com` opens Search Console — Gene
   completes the TXT-record dance in Cloudflare if prompted.
2. **[laptop] Create the mapping**:
   ```
   gcloud beta run domain-mappings create \
     --service curtaincallcfp --domain curtaincallcfp.com \
     --region us-west1 --project swyx-cfp-saas-killer
   ```
   The output lists the DNS records Google wants (A/AAAA set for apex).
3. **[Gene, Cloudflare]** Add exactly those records, **DNS-only / gray
   cloud**. Apex A/AAAA records per step 2's output; optionally
   `www` CNAME → `ghs.googlehosted.com` (and a second mapping for www).
4. **Wait for cert**: `gcloud beta run domain-mappings describe --domain
   curtaincallcfp.com --region us-west1` until CertificateProvisioned=True
   (minutes to ~1h after DNS propagates).
5. **[Gene, Google console]** Add the OAuth redirect URI to the web client
   (docs/provisioning-google-auth.md has the client page):
   `https://curtaincallcfp.com/auth/google/callback` — WITHOUT this, Google
   sign-in on the new domain answers redirect_uri_mismatch.
6. **[laptop] BASE_URL**: if the service carries a BASE_URL env (magic links,
   .ics UIDs, absolute URLs), update it to https://curtaincallcfp.com and
   redeploy env only:
   `gcloud run services update curtaincallcfp --region us-west1 --update-env-vars BASE_URL=https://curtaincallcfp.com`
7. **Verify at the meter**: curl https://curtaincallcfp.com/ (landing),
   /login (Google button), sign in end-to-end, check the callback lands.
8. **Leave the run.app URL alive** — it keeps serving (existing OAuth URI
   stays registered); nothing breaks for anyone mid-session.

## Rollback

Delete the domain mapping; Cloudflare records become inert. The run.app URL
never stopped working.
