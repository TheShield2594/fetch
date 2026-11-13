/**
 * `fetch`: recovery commands for the server's operator, run inside the API
 * container where the database is reachable:
 *
 *   docker compose exec api fetch reset-password <username>
 *   docker compose exec api fetch reset-2fa <username>
 *   docker compose exec api fetch clear-pin <profile>
 *   docker compose exec api fetch accounts
 *
 * They exist because a forgotten admin password or profile PIN otherwise
 * needed the database edited by hand: nothing on the website can reset the
 * admin's password, and nobody can open a profile whose PIN is lost. Someone
 * with a shell on the server already controls everything these commands touch.
 * Documented in docs/troubleshooting.md.
 */
import { pathToFileURL } from "node:url";
import { prisma } from "./lib/prisma.js";
import {
  clearProfilePin,
  generateTemporaryPassword,
  isValidPassword,
  PASSWORD_ERROR,
  removeTotp,
  setTemporaryPassword,
} from "./lib/account-admin.js";
import { recordAccountEvent } from "./lib/account-events.js";
import { UUID_V4_PATTERN } from "./lib/types.js";

const USAGE = `Usage: fetch <command> [options]

Commands:
  accounts                         List accounts and their profiles.
  reset-password <username>        Give an account a temporary password. It is
      [--password <password>]      signed out of every browser (not its TVs)
                                   and asked for a new password at its next
                                   sign-in. Without --password a random one is
                                   made and printed.
  reset-2fa <username>             Turn off an account's two-factor sign-in,
                                   for a lost authenticator with no recovery
                                   codes left. Its password is unchanged; it
                                   can turn two-factor on again in Settings.
  clear-pin <profile>              Remove a profile's PIN. <profile> is its id,
      [--account <username>]       or its name, in quotes if it has a space
                                   ("Kids Room"). Add --account when two
                                   accounts have a profile of that name.
`;

class UsageError extends Error {}

/** What each command accepts. Anything else is refused before it runs. */
const COMMANDS: Record<string, { args: number; argName?: string; options: string[] }> = {
  accounts: { args: 0, options: [] },
  "reset-password": { args: 1, argName: "a username", options: ["password"] },
  "reset-2fa": { args: 1, argName: "a username", options: [] },
  "clear-pin": { args: 1, argName: "a profile id or name", options: ["account"] },
};

/** `--name value` options and the positional arguments around them. */
export const parseArgs = (argv: string[]) => {
  const positional: string[] = [];
  // No prototype: `--__proto__ x` would otherwise set the object's prototype
  // rather than a key, and slip past the unknown-option check below.
  const options: Record<string, string> = Object.create(null) as Record<string, string>;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]!;
    if (arg === "--help" || arg === "-h") {
      options.help = "true";
    } else if (arg.startsWith("--")) {
      const name = arg.slice(2);
      const value = argv[i + 1];
      if (value === undefined || value.startsWith("--")) throw new UsageError(`--${name} needs a value`);
      // An empty value (`--account ""`) would read as the option being absent
      // and drop the scope it was meant to add.
      if (!value.trim()) throw new UsageError(`--${name} needs a value`);
      options[name] = value;
      i += 1;
    } else {
      positional.push(arg);
    }
  }
  return { positional, options };
};

const listAccounts = async (out: (line: string) => void) => {
  const accounts = await prisma.account.findMany({
    orderBy: { createdAt: "asc" },
    include: { profiles: { orderBy: { createdAt: "asc" }, select: { id: true, name: true, pinHash: true } } },
  });
  if (accounts.length === 0) {
    out("No accounts yet. Open the website to create the admin account.");
    return;
  }
  for (const account of accounts) {
    out(`${account.username}${account.isAdmin ? " (admin)" : ""}${account.totpSecret ? " [2FA]" : ""}`);
    for (const profile of account.profiles) {
      out(`  ${profile.name}${profile.pinHash ? " [PIN]" : ""}  ${profile.id}`);
    }
  }
};

const resetPassword = async (username: string | undefined, chosen: string | undefined, out: (line: string) => void) => {
  if (!username) throw new UsageError("reset-password needs a username");
  const account = await prisma.account.findUnique({ where: { username: username.trim().toLowerCase() } });
  if (!account) throw new UsageError(`No account is called "${username}". Run "fetch accounts" to list them.`);
  if (chosen !== undefined && !isValidPassword(chosen)) throw new UsageError(PASSWORD_ERROR);

  const password = chosen ?? generateTemporaryPassword();
  await setTemporaryPassword(account.id, password);
  await recordAccountEvent(account.id, "password_reset", { detail: "From the server" });
  out(`${account.username} has a temporary password and has been signed out of every browser.`);
  out("Their TVs stay signed in; they can sign those out in Settings → Account.");
  if (chosen === undefined) out(`Temporary password: ${password}`);
  out("They'll be asked to choose a new one when they sign in.");
};

const resetTwoFactor = async (username: string | undefined, out: (line: string) => void) => {
  if (!username) throw new UsageError("reset-2fa needs a username");
  const account = await prisma.account.findUnique({ where: { username: username.trim().toLowerCase() } });
  if (!account) throw new UsageError(`No account is called "${username}". Run "fetch accounts" to list them.`);
  if (!account.totpSecret && !account.totpPendingSecret) {
    out(`${account.username} doesn't use two-factor sign-in. Nothing to do.`);
    return;
  }
  await removeTotp(account.id);
  await recordAccountEvent(account.id, "totp_disabled", { detail: "From the server" });
  out(`Turned off two-factor sign-in for ${account.username}. They sign in with their password alone now,`);
  out("and can turn two-factor on again in Settings → Account.");
};

const clearPin = async (target: string | undefined, accountName: string | undefined, out: (line: string) => void) => {
  if (!target) throw new UsageError("clear-pin needs a profile id or name");
  const account = accountName
    ? await prisma.account.findUnique({ where: { username: accountName.trim().toLowerCase() } })
    : null;
  if (accountName && !account) throw new UsageError(`No account is called "${accountName}".`);

  const matches = await prisma.profile.findMany({
    where: {
      ...(UUID_V4_PATTERN.test(target) ? { id: target } : { name: { equals: target.trim(), mode: "insensitive" } }),
      ...(account ? { accountId: account.id } : {}),
    },
    include: { account: { select: { username: true } } },
  });
  if (matches.length === 0) throw new UsageError(`No profile matches "${target}". Run "fetch accounts" to list them.`);
  if (matches.length > 1) {
    const where = matches.map((profile) => `  ${profile.name} (${profile.account?.username ?? "no account"})  ${profile.id}`);
    throw new UsageError(`More than one profile matches "${target}". Use its id, or add --account:\n${where.join("\n")}`);
  }
  const profile = matches[0]!;
  if (!profile.pinHash) {
    out(`${profile.name} has no PIN. Nothing to do.`);
    return;
  }
  await clearProfilePin(profile.id);
  if (profile.accountId) await recordAccountEvent(profile.accountId, "pin_cleared", { detail: `${profile.name}, from the server` });
  out(`Removed the PIN from ${profile.name}. Set a new one in Settings → Profile.`);
};

export const run = async (argv: string[], out: (line: string) => void = console.log): Promise<number> => {
  try {
    const { positional, options } = parseArgs(argv);
    const [command, ...rest] = positional;
    if (!command || options.help) {
      out(USAGE);
      return command || options.help ? 0 : 1;
    }
    // Each command's options and arguments, checked before anything is read
    // or written: a misspelt option, or a profile name with a space left
    // unquoted, must be refused rather than run as if it weren't there —
    // `clear-pin Kids Room` would otherwise clear the PIN on "Kids".
    const spec = COMMANDS[command];
    if (!spec) throw new UsageError(`Unknown command "${command}".\n\n${USAGE}`);
    const unknown = Object.keys(options).filter((name) => !spec.options.includes(name));
    if (unknown.length > 0) {
      throw new UsageError(`${command} doesn't take ${unknown.map((name) => `--${name}`).join(", ")}.\n\n${USAGE}`);
    }
    if (rest.length !== spec.args) {
      throw new UsageError(
        rest.length > spec.args
          ? `${command} takes ${spec.args === 0 ? "no arguments" : "one argument"}; got ${rest.length}. Put a name with spaces in quotes: "Kids Room".`
          : `${command} needs ${spec.argName}.`
      );
    }
    switch (command) {
      case "accounts":
        await listAccounts(out);
        return 0;
      case "reset-password":
        await resetPassword(rest[0], options.password, out);
        return 0;
      case "reset-2fa":
        await resetTwoFactor(rest[0], out);
        return 0;
      default:
        await clearPin(rest[0], options.account, out);
        return 0;
    }
  } catch (error) {
    if (error instanceof UsageError) {
      console.error(error.message);
      return 2;
    }
    console.error(error instanceof Error ? error.message : error);
    return 1;
  } finally {
    await prisma.$disconnect();
  }
};

// Run when executed (`node dist/cli.js`), not when imported by a test.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await run(process.argv.slice(2));
}
