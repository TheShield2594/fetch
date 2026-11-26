import { describe, expect, it } from "vitest";
import { MIN_API_TOKEN_LENGTH, productionConfigIssues } from "./startup-config.js";

const STRONG = "a".repeat(64);
const DB = "postgresql://fetch:s3cret@db:5432/fetch";

describe("productionConfigIssues", () => {
  it("accepts a long API_TOKEN and a real database password", () => {
    expect(productionConfigIssues({ API_TOKEN: STRONG, DATABASE_URL: DB })).toEqual([]);
  });

  it("accepts a token of exactly the minimum length", () => {
    expect(productionConfigIssues({ API_TOKEN: "x".repeat(MIN_API_TOKEN_LENGTH), DATABASE_URL: DB })).toEqual([]);
  });

  it("refuses a missing token", () => {
    expect(productionConfigIssues({ DATABASE_URL: DB })).toEqual(["API_TOKEN is not set"]);
  });

  it("refuses the development default", () => {
    expect(productionConfigIssues({ API_TOKEN: "dev-token", DATABASE_URL: DB })).toEqual([
      'API_TOKEN is set to the development default "dev-token"',
    ]);
  });

  it("refuses a short token, and says how to make a strong one", () => {
    const issues = productionConfigIssues({ API_TOKEN: "hunter2", DATABASE_URL: DB });
    expect(issues).toHaveLength(1);
    expect(issues[0]).toContain(`shorter than ${MIN_API_TOKEN_LENGTH} characters`);
    expect(issues[0]).toContain("openssl rand -hex 32");
  });

  it("refuses the development Postgres password", () => {
    expect(
      productionConfigIssues({ API_TOKEN: STRONG, DATABASE_URL: "postgresql://postgres:postgres@db:5432/fetch" })
    ).toEqual(["DATABASE_URL uses the development default Postgres password"]);
  });

  it("holds FETCH_SECRET_KEY to the same length, and refuses one equal to API_TOKEN (#638)", () => {
    expect(productionConfigIssues({ API_TOKEN: STRONG, FETCH_SECRET_KEY: "b".repeat(64), DATABASE_URL: DB })).toEqual([]);
    const short = productionConfigIssues({ API_TOKEN: STRONG, FETCH_SECRET_KEY: "short", DATABASE_URL: DB });
    expect(short).toHaveLength(1);
    expect(short[0]).toContain("FETCH_SECRET_KEY is shorter than");
    const same = productionConfigIssues({ API_TOKEN: STRONG, FETCH_SECRET_KEY: STRONG, DATABASE_URL: DB });
    expect(same).toEqual([expect.stringContaining("FETCH_SECRET_KEY is the same as API_TOKEN")]);
    expect(productionConfigIssues({ API_TOKEN: STRONG, FETCH_SECRET_KEY: "", DATABASE_URL: DB })).toEqual([]);
  });
});
