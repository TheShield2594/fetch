import { afterEach, describe, expect, it, vi } from "vitest";
import {
  mailConfigFromEnv,
  normalizeEmail,
  renderMailHtml,
  sendMail,
  setMailTransportForTests,
} from "./mailer.js";

describe("normalizeEmail", () => {
  it("trims and lowercases an address", () => {
    expect(normalizeEmail("  Alex@Example.COM ")).toBe("alex@example.com");
  });

  it.each([undefined, null, 42, "", "alex", "alex@", "@example.com", "alex@example", "a b@example.com", "<a@b.c>"])(
    "rejects %s",
    (value) => {
      expect(normalizeEmail(value)).toBeNull();
    }
  );

  it("rejects an address longer than the SMTP limit", () => {
    expect(normalizeEmail(`${"a".repeat(250)}@x.io`)).toBeNull();
  });
});

describe("mailConfigFromEnv", () => {
  it("is off without SMTP_HOST", () => {
    expect(mailConfigFromEnv({})).toBeNull();
  });

  it("defaults to STARTTLS on 587 and a From built from the user", () => {
    expect(mailConfigFromEnv({ SMTP_HOST: "smtp.example.com", SMTP_USER: "me@example.com", SMTP_PASSWORD: "pw" })).toEqual({
      host: "smtp.example.com",
      port: 587,
      secure: false,
      user: "me@example.com",
      password: "pw",
      from: "Fetch <me@example.com>",
    });
  });

  it("treats port 465 as implicit TLS and honours SMTP_FROM", () => {
    expect(mailConfigFromEnv({ SMTP_HOST: "mail", SMTP_PORT: "465", SMTP_FROM: "TV <tv@home.lan>" })).toMatchObject({
      port: 465,
      secure: true,
      from: "TV <tv@home.lan>",
    });
  });

  it("lets SMTP_SECURE override the port's default", () => {
    expect(mailConfigFromEnv({ SMTP_HOST: "mail", SMTP_PORT: "2465", SMTP_SECURE: "true" })).toMatchObject({
      port: 2465,
      secure: true,
    });
  });
});

describe("renderMailHtml", () => {
  it("escapes the text and the link", () => {
    const html = renderMailHtml({
      to: "a@b.c",
      subject: "<Hi>",
      text: "Tom & <Jerry>\n\nSecond",
      action: { label: "Go", url: 'https://x.test/?a="1"' },
    });
    expect(html).toContain("Tom &amp; &lt;Jerry&gt;");
    expect(html).toContain("&lt;Hi&gt;");
    expect(html).toContain('href="https://x.test/?a=&quot;1&quot;"');
    expect(html).not.toContain("<Jerry>");
  });
});

describe("sendMail", () => {
  const original = { ...process.env };
  afterEach(() => {
    process.env = { ...original };
    setMailTransportForTests(null);
  });

  it("refuses when SMTP isn't configured", async () => {
    delete process.env.SMTP_HOST;
    await expect(sendMail({ to: "a@b.c", subject: "s", text: "t" })).rejects.toThrow(/not configured/);
  });

  it("sends text and HTML with the action link in both", async () => {
    process.env.SMTP_HOST = "mail";
    const sendMailMock = vi.fn().mockResolvedValue({});
    setMailTransportForTests({ sendMail: sendMailMock });

    await sendMail({ to: "a@b.c", subject: "Hello", text: "Body", action: { label: "Open", url: "https://x.test/p" } });

    expect(sendMailMock).toHaveBeenCalledWith(
      expect.objectContaining({
        to: "a@b.c",
        subject: "Hello",
        from: "Fetch <fetch@mail>",
        text: "Body\n\nOpen: https://x.test/p",
        html: expect.stringContaining('href="https://x.test/p"'),
      })
    );
  });
});
