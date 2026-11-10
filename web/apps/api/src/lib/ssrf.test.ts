import { describe, expect, it } from "vitest";
import {
  resolveAiProviderUrl,
  resolveNotificationUrl,
  resolvePushEndpoint,
  validateAiProviderUrl,
  validateNotificationUrl,
  validatePushEndpoint,
  type HostResolver,
} from "./ssrf.js";

// Stub resolver so the tests never touch real DNS.
const resolvesTo = (...addresses: string[]): HostResolver => async () => addresses;
const failsToResolve: HostResolver = async () => {
  throw new Error("ENOTFOUND");
};

describe("validateAiProviderUrl", () => {
  it("allows public and LAN/localhost LLM endpoints", () => {
    // Local models (Ollama, LM Studio, self-hosted) are legitimate targets.
    expect(validateAiProviderUrl("https://api.openai.com/v1/chat/completions")).not.toBeNull();
    expect(validateAiProviderUrl("http://localhost:11434/v1/chat/completions")).not.toBeNull();
    expect(validateAiProviderUrl("http://192.168.1.50:1234/v1")).not.toBeNull();
    expect(validateAiProviderUrl("http://127.0.0.1:8080/")).not.toBeNull();
  });

  it("rejects non-HTTP schemes and unparseable URLs", () => {
    expect(validateAiProviderUrl("file:///etc/passwd")).toBeNull();
    expect(validateAiProviderUrl("gopher://x/")).toBeNull();
    expect(validateAiProviderUrl("not a url")).toBeNull();
  });

  it("blocks the cloud-metadata / link-local range and unspecified address", () => {
    expect(validateAiProviderUrl("http://169.254.169.254/latest/meta-data/")).toBeNull();
    expect(validateAiProviderUrl("http://0.0.0.0/")).toBeNull();
    expect(validateAiProviderUrl("http://[fe80::1]/")).toBeNull();
    expect(validateAiProviderUrl("http://[fd00:ec2::254]/")).toBeNull();
    expect(validateAiProviderUrl("http://[::]/")).toBeNull();
  });

  it("blocks the metadata address expressed as an IPv4-mapped IPv6 literal", () => {
    // Node normalizes these to "::ffff:a9fe:a9fe", which must still be blocked.
    expect(validateAiProviderUrl("http://[::ffff:169.254.169.254]/latest/meta-data/")).toBeNull();
    expect(validateAiProviderUrl("http://[::ffff:a9fe:a9fe]/")).toBeNull();
  });

  it("blocks the metadata address written in a non-dotted-quad notation", () => {
    // WHATWG URL parsing canonicalizes these back to 169.254.169.254.
    expect(validateAiProviderUrl("http://2852039166/latest/meta-data/")).toBeNull();
    expect(validateAiProviderUrl("http://0xA9FEA9FE/")).toBeNull();
    expect(validateAiProviderUrl("http://0251.0376.0251.0376/")).toBeNull();
  });
});

describe("resolveAiProviderUrl", () => {
  it("allows a hostname that resolves to a public or LAN address", async () => {
    const url = await resolveAiProviderUrl("https://api.openai.com/v1", resolvesTo("104.18.6.192"));
    expect(url?.host).toBe("api.openai.com");
    expect(await resolveAiProviderUrl("http://ollama.lan:11434/v1", resolvesTo("192.168.1.50"))).not.toBeNull();
  });

  it("blocks a public hostname that resolves to the metadata service", async () => {
    // The whole point: an ordinary-looking name passes every syntactic check,
    // and only its A record gives it away.
    expect(validateAiProviderUrl("http://metadata.google.internal/")).not.toBeNull();
    expect(
      await resolveAiProviderUrl("http://metadata.google.internal/", resolvesTo("169.254.169.254"))
    ).toBeNull();
    expect(
      await resolveAiProviderUrl("http://llm.attacker.example/v1", resolvesTo("169.254.169.254"))
    ).toBeNull();
  });

  it("blocks when only one of several resolved addresses is a blocked target", async () => {
    expect(
      await resolveAiProviderUrl("https://llm.example.com/v1", resolvesTo("93.184.216.34", "169.254.169.254"))
    ).toBeNull();
  });

  it("blocks a hostname resolving to a link-local IPv6 or mapped IPv4 address", async () => {
    expect(await resolveAiProviderUrl("https://llm.example.com/v1", resolvesTo("fe80::1"))).toBeNull();
    expect(
      await resolveAiProviderUrl("https://llm.example.com/v1", resolvesTo("::ffff:169.254.169.254"))
    ).toBeNull();
  });

  it("fails closed on an unresolvable or empty-answer hostname", async () => {
    expect(await resolveAiProviderUrl("https://nope.example.com/v1", failsToResolve)).toBeNull();
    expect(await resolveAiProviderUrl("https://nope.example.com/v1", resolvesTo())).toBeNull();
  });

  it("still rejects everything the syntactic check rejects, without resolving", async () => {
    const resolver: HostResolver = async () => {
      throw new Error("should not be called for an IP literal");
    };
    expect(await resolveAiProviderUrl("http://169.254.169.254/", resolver)).toBeNull();
    expect(await resolveAiProviderUrl("file:///etc/passwd", resolver)).toBeNull();
    // IP literals skip DNS entirely — the literal check already covered them.
    expect(await resolveAiProviderUrl("http://127.0.0.1:8080/", resolver)).not.toBeNull();
    expect(await resolveAiProviderUrl("http://[::1]:8080/", resolver)).not.toBeNull();
  });
});

describe("notification channel URLs", () => {
  it("allows the LAN and localhost targets these channels are for", () => {
    // A self-hosted ntfy or Gotify on the LAN is the intended configuration,
    // so the push endpoint's private-range block would reject the common case.
    expect(validateNotificationUrl("https://ntfy.sh/my-topic")).not.toBeNull();
    expect(validateNotificationUrl("http://192.168.1.25:8080/fetch")).not.toBeNull();
    expect(validateNotificationUrl("http://localhost:2586")).not.toBeNull();
    expect(validateNotificationUrl("https://discord.com/api/webhooks/1/abc")).not.toBeNull();
  });

  it("still blocks the metadata range and non-HTTP schemes", () => {
    expect(validateNotificationUrl("http://169.254.169.254/latest/meta-data/")).toBeNull();
    expect(validateNotificationUrl("http://0.0.0.0/x")).toBeNull();
    expect(validateNotificationUrl("file:///etc/passwd")).toBeNull();
    expect(validateNotificationUrl("gopher://ntfy.sh/topic")).toBeNull();
    expect(validateNotificationUrl("not a url")).toBeNull();
  });

  it("blocks a public hostname that resolves to the metadata service", async () => {
    expect(validateNotificationUrl("https://ntfy.example.com/topic")).not.toBeNull();
    expect(
      await resolveNotificationUrl("https://ntfy.example.com/topic", resolvesTo("169.254.169.254"))
    ).toBeNull();
    expect(await resolveNotificationUrl("https://ntfy.example.com/topic", resolvesTo("10.1.2.3"))).not.toBeNull();
  });

  it("fails closed when the hostname cannot be resolved", async () => {
    expect(await resolveNotificationUrl("https://ntfy.example.com/topic", failsToResolve)).toBeNull();
  });
});

describe("validatePushEndpoint", () => {
  it("allows real browser push services", () => {
    expect(validatePushEndpoint("https://fcm.googleapis.com/fcm/send/abc")).not.toBeNull();
    expect(validatePushEndpoint("https://updates.push.services.mozilla.com/wpush/v2/abc")).not.toBeNull();
  });

  it("rejects non-https and private/loopback/link-local literals", () => {
    expect(validatePushEndpoint("http://fcm.googleapis.com/fcm/send/abc")).toBeNull();
    expect(validatePushEndpoint("https://localhost/push")).toBeNull();
    expect(validatePushEndpoint("https://127.0.0.1/push")).toBeNull();
    expect(validatePushEndpoint("https://10.0.0.5/push")).toBeNull();
    expect(validatePushEndpoint("https://192.168.1.5/push")).toBeNull();
    expect(validatePushEndpoint("https://172.20.0.1/push")).toBeNull();
    expect(validatePushEndpoint("https://169.254.169.254/push")).toBeNull();
    expect(validatePushEndpoint("https://[::1]/push")).toBeNull();
    expect(validatePushEndpoint("https://[fd00::1]/push")).toBeNull();
    expect(validatePushEndpoint("not a url")).toBeNull();
  });
});

describe("the private-range check behind the push and public-only policies", () => {
  const blockedForPublicOnly = async (host: string) => {
    const { PUBLIC_ONLY } = await import("./ssrf.js");
    return validateAiProviderUrl(`https://${host}/x`, PUBLIC_ONLY) === null;
  };

  it.each([
    ["100.64.0.1", "carrier-grade NAT"],
    ["100.127.255.254", "carrier-grade NAT, top of the range"],
    ["198.18.0.1", "benchmarking"],
    ["198.19.255.255", "benchmarking, top of the range"],
    ["0.1.2.3", "this network"],
    ["224.0.0.1", "multicast"],
    ["239.255.255.250", "multicast (SSDP)"],
    ["240.0.0.1", "reserved"],
    ["255.255.255.255", "broadcast"],
    ["[ff02::1]", "IPv6 multicast"],
    ["[febf::1]", "the top of fe80::/10"],
  ])("blocks %s (%s)", async (host) => {
    expect(validatePushEndpoint(`https://${host}/push`)).toBeNull();
    expect(await blockedForPublicOnly(host)).toBe(true);
  });

  it.each([
    ["[64:ff9b::7f00:1]", "NAT64 of 127.0.0.1"],
    ["[64:ff9b::10.0.0.5]", "NAT64 of 10.0.0.5, dotted"],
    ["[::7f00:1]", "IPv4-compatible 127.0.0.1"],
    ["[::192.168.1.5]", "IPv4-compatible 192.168.1.5"],
    ["[::ffff:a00:5]", "IPv4-mapped 10.0.0.5"],
    ["[::ffff:0:a00:5]", "IPv4-translated 10.0.0.5"],
    ["[::ffff:100.64.0.1]", "IPv4-mapped carrier-grade NAT"],
  ])("blocks a private IPv4 address written as IPv6: %s (%s)", async (host) => {
    expect(validatePushEndpoint(`https://${host}/push`)).toBeNull();
    expect(await blockedForPublicOnly(host)).toBe(true);
  });

  it("blocks the metadata address in NAT64 and IPv4-compatible form even for the admin", () => {
    expect(validateAiProviderUrl("http://[64:ff9b::a9fe:a9fe]/")).toBeNull();
    expect(validateAiProviderUrl("http://[::169.254.169.254]/")).toBeNull();
  });

  it("blocks addresses that resolve into the new ranges", async () => {
    for (const address of ["100.64.0.1", "198.18.0.1", "224.0.0.1", "64:ff9b::a00:5", "::ffff:10.0.0.5"]) {
      expect(await resolvePushEndpoint("https://push.example.com/x", resolvesTo(address))).toBeNull();
    }
  });

  it.each(["8.8.8.8", "100.63.255.255", "100.128.0.1", "198.17.255.255", "198.20.0.1", "223.255.255.255", "[2606:4700::1111]", "[64:ff9b::808:808]"])(
    "still allows the public address %s",
    async (host) => {
      expect(validatePushEndpoint(`https://${host}/push`)).not.toBeNull();
      expect(await blockedForPublicOnly(host)).toBe(false);
    }
  );

  it("leaves the admin's LAN and loopback targets alone", () => {
    for (const url of ["http://100.64.0.1/", "http://127.0.0.1/", "http://[::1]/", "http://10.0.0.5/"]) {
      expect(validateAiProviderUrl(url)).not.toBeNull();
    }
  });
});

describe("resolvePushEndpoint", () => {
  it("allows a push service resolving to a public address", async () => {
    expect(
      await resolvePushEndpoint("https://fcm.googleapis.com/fcm/send/abc", resolvesTo("142.250.185.10"))
    ).not.toBeNull();
  });

  it("blocks a public hostname that resolves to a private or loopback address", async () => {
    // "localtest.me" and friends are public names pointing at 127.0.0.1.
    expect(validatePushEndpoint("https://localtest.me/push")).not.toBeNull();
    expect(await resolvePushEndpoint("https://localtest.me/push", resolvesTo("127.0.0.1"))).toBeNull();
    expect(await resolvePushEndpoint("https://push.example.com/x", resolvesTo("10.1.2.3"))).toBeNull();
    expect(await resolvePushEndpoint("https://push.example.com/x", resolvesTo("::1"))).toBeNull();
  });

  it("fails closed when the hostname cannot be resolved", async () => {
    expect(await resolvePushEndpoint("https://push.example.com/x", failsToResolve)).toBeNull();
  });
});

describe("the public-only scope another account's integrations get", () => {
  it("refuses private and loopback hosts the admin may use", async () => {
    const { PUBLIC_ONLY, PRIVATE_NETWORK_ALLOWED } = await import("./ssrf.js");
    for (const url of ["http://192.168.1.20:11434/v1", "http://10.0.0.5/hook", "http://localhost:8080", "http://127.0.0.1/x"]) {
      expect(validateAiProviderUrl(url, PRIVATE_NETWORK_ALLOWED)).not.toBeNull();
      expect(validateAiProviderUrl(url, PUBLIC_ONLY)).toBeNull();
      expect(validateNotificationUrl(url, PUBLIC_ONLY)).toBeNull();
    }
    expect(validateAiProviderUrl("https://api.example.com/v1", PUBLIC_ONLY)).not.toBeNull();
  });

  it("refuses a public name that resolves to a private address", async () => {
    const { PUBLIC_ONLY } = await import("./ssrf.js");
    const resolveToLan = async () => ["192.168.1.20"];
    expect(await resolveAiProviderUrl("https://llm.example.com/v1", resolveToLan, PUBLIC_ONLY)).toBeNull();
    expect(await resolveNotificationUrl("https://ntfy.example.com/t", resolveToLan, PUBLIC_ONLY)).toBeNull();
    expect(await resolveAiProviderUrl("https://llm.example.com/v1", resolveToLan)).not.toBeNull();
  });
});
