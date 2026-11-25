import { describe, expect, it } from "vitest";
import { parseLogLevel, requestLogLevel } from "./request-logging.js";

describe("parseLogLevel", () => {
  it.each([
    ["debug", "debug"],
    [" WARN ", "warn"],
    ["silent", "silent"],
    [undefined, "info"],
    ["", "info"],
    ["verbose", "info"],
  ])("reads %j as %s", (raw, expected) => {
    expect(parseLogLevel(raw)).toBe(expected);
  });
});

describe("requestLogLevel", () => {
  it("never logs a healthcheck, even a failing one", () => {
    expect(requestLogLevel("/health/ready", 200, 2)).toBeNull();
    expect(requestLogLevel("/health", 503, 2)).toBeNull();
    expect(requestLogLevel("/health/ready?x=1", 200, 5000)).toBeNull();
  });

  it("does not mistake another route for a healthcheck", () => {
    expect(requestLogLevel("/healthy", 200, 2)).toBe("debug");
  });

  it("keeps an ordinary success out of the default log", () => {
    expect(requestLogLevel("/lists", 200, 12)).toBe("debug");
    expect(requestLogLevel("/lists", 304, 12)).toBe("debug");
  });

  it("logs a slow success at info", () => {
    expect(requestLogLevel("/lists", 200, 1500)).toBe("info");
  });

  it("logs a client error at warn and a server error at error", () => {
    expect(requestLogLevel("/lists", 404, 3)).toBe("warn");
    expect(requestLogLevel("/lists", 500, 3)).toBe("error");
  });
});
