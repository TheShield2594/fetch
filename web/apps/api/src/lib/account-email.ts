// The mails an account gets about itself: confirming its address, resetting
// its password, and the invite that starts it. Each carries a single-use link
// whose secret is stored only as a digest (like session tokens and invite
// codes), so a database dump can't be used to confirm or reset anything.

import { randomBytes } from "node:crypto";
import { prisma } from "./prisma.js";
import { hashSecret } from "./request-auth.js";
import { isMailConfigured, sendMail, webLink } from "./mailer.js";

export const EMAIL_VERIFY_TTL_MS = 48 * 60 * 60 * 1000;
export const PASSWORD_RESET_TTL_MS = 60 * 60 * 1000;

const mintLinkSecret = () => randomBytes(32).toString("base64url");

/**
 * Whether this server can send the mails these flows depend on: SMTP to send
 * them, and the website's address to link to.
 */
export const isAccountEmailAvailable = (): boolean => isMailConfigured() && webLink("/") !== null;

/**
 * Mails a confirmation link for the account's current address, replacing any
 * earlier link. Returns false (without throwing) when nothing could be sent.
 */
export const sendEmailVerification = async (account: { id: string; username: string; email: string }): Promise<boolean> => {
  if (!isAccountEmailAvailable()) return false;
  const secret = mintLinkSecret();
  const url = webLink(`/verify-email/${secret}`);
  if (!url) return false;
  await prisma.account.update({
    where: { id: account.id },
    data: { emailVerifyTokenHash: hashSecret(secret), emailVerifyExpiresAt: new Date(Date.now() + EMAIL_VERIFY_TTL_MS) },
  });
  await sendMail({
    to: account.email,
    subject: "Confirm your email for Fetch",
    text: `Hi ${account.username},\n\nConfirm this address to use it for password resets and notifications from Fetch. The link works for 48 hours.\n\nIf you didn't add this address to a Fetch account, you can ignore this email.`,
    action: { label: "Confirm email", url },
  });
  return true;
};

/** Mails a password-reset link, replacing any earlier one. */
export const sendPasswordReset = async (account: { id: string; username: string; email: string }): Promise<void> => {
  const secret = mintLinkSecret();
  const url = webLink(`/reset-password/${secret}`);
  if (!url) return;
  await prisma.account.update({
    where: { id: account.id },
    data: {
      passwordResetTokenHash: hashSecret(secret),
      passwordResetExpiresAt: new Date(Date.now() + PASSWORD_RESET_TTL_MS),
    },
  });
  await sendMail({
    to: account.email,
    subject: "Reset your Fetch password",
    text: `Hi ${account.username},\n\nSomeone asked to reset the password for your Fetch account. The link works for one hour and can be used once. Resetting signs out every device.\n\nIf it wasn't you, ignore this email: your password stays as it is.`,
    action: { label: "Choose a new password", url },
  });
};

export const sendInviteEmail = async (to: string, url: string, expiresAt: Date, invitedBy: string | null) => {
  const who = invitedBy ? `${invitedBy} has invited you` : "You've been invited";
  await sendMail({
    to,
    subject: "You're invited to Fetch",
    text: `${who} to an account on their Fetch server, where they keep track of shows, films and games.\n\nThe link works once, until ${expiresAt.toUTCString()}.`,
    action: { label: "Create your account", url },
  });
};
