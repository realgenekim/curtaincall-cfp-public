# Email and blob provider ports

The application owns two small effect algebras:

- `cfp-scheduler-killer.io.email`: configure, identify, and send one
  provider-neutral message.
- `cfp-scheduler-killer.io.blob`: put, read, and copy bytes.

Views, folds, and pure decisions never import provider namespaces. Tests bind
the port functions to recording fakes, so no test needs credentials or network
access. The application-level `mail/send!` remains responsible for appending
`comms.sent` or `comms.failed`; a provider response is never silently treated as
domain truth.

## Runtime email configuration

Local development reads `secrets/email.edn`. Deployed instances read the
`email-provider` secret through the existing runtime secret loader. Email
credentials are not environment variables and must not be committed.

Resend REST:

```clojure
{:provider :resend
 :api-key "re_..."
 :from "Conference <cfp@example.com>"}
```

Cloudflare Email Service REST:

```clojure
{:provider :cloudflare
 :account-id "..."
 :api-token "..."
 :from "Conference <cfp@example.com>"}
```

AWS SES over SMTP:

```clojure
{:provider :aws-ses
 :host "email-smtp.us-west-2.amazonaws.com"
 :port 587
 :user "..."
 :pass "..."
 :from "Conference <cfp@example.com>"}
```

Generic SMTP, including Cloudflare SMTP Submission:

```clojure
{:provider :smtp
 :host "smtp.mx.cloudflare.net"
 :port 465
 :user "api_token"
 :pass "..."
 :from "Conference <cfp@example.com>"}
```

## Provider constraints

- Resend uses `POST /emails` with bearer authentication. Its API supports an
  `Idempotency-Key` for 24 hours and limits a message's combined attachments to
  40 MB. See the official [send-email API](https://resend.com/docs/api-reference/emails/send-email)
  and [idempotency documentation](https://resend.com/docs/dashboard/emails/idempotency-keys).
- Cloudflare Email Service REST is public beta. Sending uses
  `POST /accounts/{account_id}/email/sending/send`; the sending domain must use
  Cloudflare authoritative DNS, general sending requires Workers Paid, and
  attachments total at most 5 MiB. See Cloudflare's official
  [REST API](https://developers.cloudflare.com/email-service/api/send-emails/rest-api/)
  and [getting-started requirements](https://developers.cloudflare.com/email-service/get-started/send-emails/).
- Cloudflare SMTP Submission is also beta. It uses implicit TLS on
  `smtp.mx.cloudflare.net:465`, username `api_token`, and an API token as the
  password. See the official [SMTP beta announcement](https://developers.cloudflare.com/changelog/post/2026-06-08-smtp-submission/).
- AWS SES supports SMTP and its v2 API. This adapter deliberately uses the
  common SMTP contract today, which keeps SES and Cloudflare SMTP interchangeable
  without importing either provider into domain code. See the official
  [SES SendEmail reference](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html)
  and [attachment guidance](https://docs.aws.amazon.com/ses/latest/dg/attachments.html).

## Blob routing

When `UPLOAD_GCS_BUCKET` is present, `io.blob/put!` selects the GCS adapter;
otherwise it writes below the local upload root. Any `gs://` source or
destination selects the GCS adapter for reads and copies. GCS authentication,
JSON API URLs, HTTP transport, and the laptop-only `gcloud storage cp` fallback
live exclusively in `io.blob.gcs`.

`UPLOAD_GCS_BUCKET` is routing metadata, not a credential. GCS credentials
continue to come from Application Default Credentials or the Cloud Run metadata
server.

## Adding another provider

1. Implement the existing normalized result contract:
   `{:ok true :message-id ...}` or `{:ok false :error ...}` for email, and
   `{:ok true}` or `{:ok false :error ...}` for blob copy.
2. Add one dispatcher case in the owning port.
3. Run the adapter against the recording contract tests. Do not add provider
   conditionals to views, folds, handlers, or domain decisions.
4. Keep provider credentials in the runtime secret document and record the
   normalized outcome through the application façade.
