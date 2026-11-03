import { describe, expect, it } from "vitest";
import { renderOAuthHtml } from "./html.js";

describe("renderOAuthHtml", () => {
  it("renders at device width with a language and a main landmark", () => {
    const html = renderOAuthHtml("Something went wrong.");
    expect(html).toContain('<html lang="en">');
    expect(html).toContain('<meta name="viewport" content="width=device-width, initial-scale=1">');
    expect(html).toMatch(/<main[\s>]/);
  });

  it("says the closing line once, even when the detail is the closing line", () => {
    const html = renderOAuthHtml("You can close this tab and return to Fetch.", "Trakt connected");
    expect(html.match(/You can close this tab and return to Fetch\./g)).toHaveLength(1);
  });

  it("adds the closing line under any other detail", () => {
    const html = renderOAuthHtml("Your Trakt account is now linked to Fetch.", "Trakt connected");
    expect(html).toContain("Your Trakt account is now linked to Fetch.");
    expect(html.match(/You can close this tab and return to Fetch\./g)).toHaveLength(1);
  });

  it("escapes the title and detail", () => {
    const html = renderOAuthHtml("<script>x</script>", "<b>t</b>");
    expect(html).not.toContain("<script>x</script>");
    expect(html).toContain("&lt;script&gt;");
  });
});
