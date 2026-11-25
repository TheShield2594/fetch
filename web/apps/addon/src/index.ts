// First: fills the public URLs from FETCH_PUBLIC_URL before anything reads them.
import "./lib/public-url-env.js";
import { Sentry } from "./lib/sentry.js";
import { app } from "./app.js";
import { ADDON_CREDENTIALS } from "./lib/config.js";

const start = async () => {
  // Without a credential every API call is refused, and every install would
  // quietly show empty rows. `app.ts` has already logged what to set. A dev
  // server (`pnpm dev`) starts anyway, as it always has.
  if (!ADDON_CREDENTIALS && process.env.NODE_ENV === "production") {
    throw new Error("Refusing to start without a valid FETCH_ADDON_KEY");
  }
  const port = Number(process.env.PORT ?? 7001);
  await app.listen({ port, host: "0.0.0.0" });
};

let shuttingDown = false;
const shutdown = async (signal: string) => {
  if (shuttingDown) {
    return;
  }

  shuttingDown = true;
  app.log.info({ signal }, "Shutting down addon service");

  try {
    await app.close();
    app.log.info("Addon service shutdown complete");
    process.exit(0);
  } catch (error) {
    app.log.error(error, "Error during addon shutdown");
    process.exit(1);
  }
};

process.on("SIGINT", () => {
  void shutdown("SIGINT");
});

process.on("SIGTERM", () => {
  void shutdown("SIGTERM");
});

start().catch((error) => {
  app.log.error(error);
  Sentry.captureException(error);
  process.exit(1);
});
