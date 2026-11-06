import { describe, expect, it } from "vitest";
import { isValidPinFormat, MAX_PIN_LENGTH, MIN_PIN_LENGTH } from "./pin.js";

describe("isValidPinFormat", () => {
  it("accepts 4 to 8 digits", () => {
    expect(isValidPinFormat("1".repeat(MIN_PIN_LENGTH))).toBe(true);
    expect(isValidPinFormat("0123")).toBe(true);
    expect(isValidPinFormat("1".repeat(MAX_PIN_LENGTH))).toBe(true);
  });

  it("rejects too short or too long", () => {
    expect(isValidPinFormat("")).toBe(false);
    expect(isValidPinFormat("1".repeat(MIN_PIN_LENGTH - 1))).toBe(false);
    expect(isValidPinFormat("1".repeat(MAX_PIN_LENGTH + 1))).toBe(false);
  });

  it("rejects anything that isn't an ASCII digit", () => {
    expect(isValidPinFormat("abcd")).toBe(false);
    expect(isValidPinFormat("12a4")).toBe(false);
    expect(isValidPinFormat("12 34")).toBe(false);
    expect(isValidPinFormat(" 1234")).toBe(false);
    expect(isValidPinFormat("1234\n")).toBe(false);
    // Full-width / Arabic-Indic digits: not on any keypad Fetch draws.
    expect(isValidPinFormat("１２３４")).toBe(false);
    expect(isValidPinFormat("١٢٣٤")).toBe(false);
  });
});
