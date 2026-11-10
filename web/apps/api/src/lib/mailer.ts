// Outbound email over SMTP: account confirmation, password resets, invites,
// and notifications for accounts that turned them on.
//
// Optional, like every other outside service. With no SMTP_HOST the website
// works as it did before email existed: an address can still be saved and used
// to sign in, but nothing is sent, so it can't be confirmed, and resets and
// emailed notifications aren't offered (`/auth/status` says so).
//
// The server is the operator's own, set in the environment, never something an
// account can point elsewhere — so unlike the notification channels there is no
// SSRF check here: the operator can already reach whatever they configure.

import nodemailer, { type Transporter } from "nodemailer";
import { escapeHtml } from "./html.js";

export type MailMessage = {
  to: string;
  subject: string;
  /** Plain-text body. The HTML part is built from it, so the two always agree. */
  text: string;
  /** A button under the text, for the one link most mails exist to deliver. */
  action?: { label: string; url: string };
};

type MailConfig = {
  host: string;
  port: number;
  secure: boolean;
  user?: string | undefined;
  password?: string | undefined;
  from: string;
};

const SEND_TIMEOUT_MS = 15_000;

const parsePort = (raw: string | undefined, secure: boolean): number => {
  const port = Number(raw);
  return Number.isInteger(port) && port > 0 && port < 65536 ? port : secure ? 465 : 587;
};

/** Read from the environment each time, so tests can set it per case. */
export const mailConfigFromEnv = (env: NodeJS.ProcessEnv = process.env): MailConfig | null => {
  const host = env.SMTP_HOST?.trim();
  if (!host) return null;
  // Port 465 is implicit TLS; anything else starts plain and upgrades with
  // STARTTLS, which nodemailer does on its own when the server offers it.
  const secureRaw = env.SMTP_SECURE?.trim().toLowerCase();
  const secure = secureRaw ? secureRaw === "true" || secureRaw === "1" : env.SMTP_PORT?.trim() === "465";
  const user = env.SMTP_USER?.trim() || undefined;
  return {
    host,
    port: parsePort(env.SMTP_PORT?.trim(), secure),
    secure,
    user,
    password: env.SMTP_PASSWORD || undefined,
    from: env.SMTP_FROM?.trim() || (user && user.includes("@") ? `Fetch <${user}>` : `Fetch <fetch@${host}>`),
  };
};

export const isMailConfigured = (): boolean => mailConfigFromEnv() !== null;

let transport: { key: string; transporter: Transporter } | null = null;
let testTransport: Pick<Transporter, "sendMail"> | null = null;

/** Replaces the SMTP connection in tests; `null` restores the real one. */
export const setMailTransportForTests = (replacement: Pick<Transporter, "sendMail"> | null): void => {
  testTransport = replacement;
};

const transporterFor = (config: MailConfig): Pick<Transporter, "sendMail"> => {
  if (testTransport) return testTransport;
  const key = JSON.stringify(config);
  if (transport?.key !== key) {
    transport = {
      key,
      transporter: nodemailer.createTransport({
        host: config.host,
        port: config.port,
        secure: config.secure,
        ...(config.user ? { auth: { user: config.user, pass: config.password ?? "" } } : {}),
        connectionTimeout: SEND_TIMEOUT_MS,
        greetingTimeout: SEND_TIMEOUT_MS,
        socketTimeout: SEND_TIMEOUT_MS,
      }),
    };
  }
  return transport.transporter;
};

/** The HTML part: the text with its paragraphs kept, and the action as a button. */
export const renderMailHtml = (message: MailMessage): string => {
  const paragraphs = message.text
    .split(/\n{2,}/)
    .map((block) => `<p style="margin:0 0 1em">${escapeHtml(block).replace(/\n/g, "<br>")}</p>`)
    .join("");
  const button = message.action
    ? `<p style="margin:1.5em 0"><a href="${escapeHtml(message.action.url)}" style="display:inline-block;background:#f97316;color:#fff;text-decoration:none;padding:0.7em 1.4em;border-radius:0.6em;font-weight:600">${escapeHtml(message.action.label)}</a></p>`
    : "";
  return `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escapeHtml(message.subject)}</title></head><body style="margin:0;padding:2em 1.5em;font-family:system-ui,sans-serif;font-size:16px;line-height:1.5;color:#0f172a"><div style="max-width:36em;margin:0 auto">${paragraphs}${button}<p style="margin:2em 0 0;color:#64748b;font-size:13px">Sent by your Fetch server.</p></div></body></html>`;
};

/**
 * Sends one message. Throws when SMTP isn't configured or the server refuses
 * it; callers that must not fail on a mail (sign-up, notifications) catch.
 */
export const sendMail = async (message: MailMessage): Promise<void> => {
  const config = mailConfigFromEnv();
  if (!config) throw new Error("Email is not configured on this server (SMTP_HOST is unset)");
  const text = message.action ? `${message.text}\n\n${message.action.label}: ${message.action.url}` : message.text;
  await transporterFor(config).sendMail({
    from: config.from,
    to: message.to,
    subject: message.subject,
    text,
    html: renderMailHtml(message),
  });
};

// ─── Addresses ───

const EMAIL_PATTERN = /^[^\s@<>()[\]\\,;:"]+@[^\s@<>()[\]\\,;:"]+\.[^\s@<>()[\]\\,;:"]+$/;
export const MAX_EMAIL_LENGTH = 254;

/** A trimmed, lowercased address, or null when it isn't one. */
export const normalizeEmail = (value: unknown): string | null => {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email.length <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.test(email) ? email : null;
};

// ─── Links ───

const WEB_PUBLIC_BASE = () => (process.env.FETCH_WEB_PUBLIC ?? process.env.WEB_PUBLIC_BASE)?.replace(/\/+$/, "") ?? null;

/**
 * An absolute link into the website, or null when the API hasn't been told
 * where the website lives. A mail with a relative link is useless, so the
 * routes that need one refuse to send without it.
 */
export const webLink = (path: string): string | null => {
  const base = WEB_PUBLIC_BASE();
  return base ? `${base}${path.startsWith("/") ? path : `/${path}`}` : null;
};
