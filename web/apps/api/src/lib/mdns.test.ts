import { describe, expect, it } from "vitest";
import {
  addressesFor,
  answerFor,
  encodeResponse,
  encodeTxt,
  fetchTxtRecord,
  isMdnsEnabled,
  lanAddresses,
  mdnsConfigFromEnv,
  parseQuery,
  SERVICE_TYPE,
  type FetchServiceInfo,
} from "./mdns.js";

function encodeName(name: string): Buffer {
  return Buffer.concat([
    ...name.split(".").map((label) => Buffer.concat([Buffer.from([label.length]), Buffer.from(label)])),
    Buffer.from([0]),
  ]);
}

function query(questions: { name: string; type: number; qu?: boolean }[]): Buffer {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(questions.length, 4);
  return Buffer.concat([
    header,
    ...questions.map((question) => {
      const tail = Buffer.alloc(4);
      tail.writeUInt16BE(question.type, 0);
      tail.writeUInt16BE(1 | (question.qu ? 0x8000 : 0), 2);
      return Buffer.concat([encodeName(question.name), tail]);
    }),
  ]);
}

const info: FetchServiceInfo = {
  instance: "Fetch (nas)",
  host: "nas",
  port: 7000,
  txt: { v: "1", api: "http://192.168.1.25:7000", web: "http://192.168.1.25:7002" },
  addresses: ["192.168.1.25"],
};

describe("parseQuery", () => {
  it("reads the questions of a query", () => {
    expect(parseQuery(query([{ name: SERVICE_TYPE, type: 12, qu: true }]))?.questions).toEqual([
      { name: SERVICE_TYPE, type: 12, unicast: true },
    ]);
  });

  it("follows compression pointers", () => {
    // Second question is "_fetch._tcp.local" written as a pointer to the first.
    const first = query([{ name: SERVICE_TYPE, type: 12 }]);
    const pointer = Buffer.from([0xc0, 12, 0, 33, 0, 1]);
    const packet = Buffer.concat([first, pointer]);
    packet.writeUInt16BE(2, 4);
    expect(parseQuery(packet)?.questions.map((question) => question.name)).toEqual([SERVICE_TYPE, SERVICE_TYPE]);
  });

  it("ignores responses and malformed packets", () => {
    const response = encodeResponse([]);
    expect(parseQuery(response)).toBeNull();
    expect(parseQuery(Buffer.from([1, 2, 3]))).toBeNull();
    const loop = Buffer.concat([Buffer.alloc(12), Buffer.from([0xc0, 12, 0, 12, 0, 1])]);
    loop.writeUInt16BE(1, 4);
    expect(parseQuery(loop)).toBeNull();
  });
});

describe("answerFor", () => {
  it("answers a browse with the PTR and puts SRV, TXT and A in additionals", () => {
    const reply = answerFor([{ name: SERVICE_TYPE, type: 12, unicast: false }], info);
    expect(reply?.answers.map((record) => record.type)).toEqual([12]);
    expect(reply?.additionals.map((record) => record.type).sort()).toEqual([1, 16, 33]);
  });

  it("answers SRV and TXT for the instance, case-insensitively", () => {
    const reply = answerFor([{ name: `fetch (NAS).${SERVICE_TYPE}`, type: 255, unicast: false }], info);
    expect(reply?.answers.map((record) => record.type).sort()).toEqual([16, 33]);
    expect(reply?.additionals.map((record) => record.type)).toEqual([1]);
  });

  it("answers the host's address", () => {
    const reply = answerFor([{ name: "nas.local", type: 1, unicast: false }], info);
    expect(reply?.answers[0]?.data).toEqual(Buffer.from([192, 168, 1, 25]));
  });

  it("stays quiet about other services", () => {
    expect(answerFor([{ name: "_googlecast._tcp.local", type: 12, unicast: false }], info)).toBeNull();
  });

  it("encodes a response that a parser can walk", () => {
    const reply = answerFor([{ name: SERVICE_TYPE, type: 12, unicast: false }], info)!;
    const packet = encodeResponse(reply.answers, reply.additionals);
    expect(packet.readUInt16BE(2)).toBe(0x8400);
    expect(packet.readUInt16BE(6)).toBe(1);
    expect(packet.readUInt16BE(10)).toBe(3);
    expect(packet.includes(Buffer.from("api=http://192.168.1.25:7000"))).toBe(true);
  });

  it("echoes the id and question for a one-shot resolver", () => {
    const asked = query([{ name: SERVICE_TYPE, type: 12 }]);
    asked.writeUInt16BE(0x1234, 0);
    const parsed = parseQuery(asked)!;
    const reply = answerFor(parsed.questions, info)!;
    const packet = encodeResponse(reply.answers, reply.additionals, parsed);
    expect(packet.readUInt16BE(0)).toBe(0x1234);
    expect(packet.readUInt16BE(4)).toBe(1);
    expect(packet.subarray(12, asked.length)).toEqual(asked.subarray(12));
  });
});

describe("encodeTxt", () => {
  it("length-prefixes each key=value", () => {
    expect(encodeTxt({ v: "1" })).toEqual(Buffer.from([3, ...Buffer.from("v=1")]));
  });

  it("is a single empty string when there is nothing to say", () => {
    expect(encodeTxt({})).toEqual(Buffer.from([0]));
  });
});

describe("addresses", () => {
  const local = [
    { name: "eth0", address: "192.168.1.25", netmask: "255.255.255.0" },
    { name: "eth1", address: "10.0.0.5", netmask: "255.0.0.0" },
  ];

  it("offers only the address on the asker's subnet", () => {
    expect(addressesFor("192.168.1.80", local)).toEqual(["192.168.1.25"]);
    expect(addressesFor("10.9.9.9", local)).toEqual(["10.0.0.5"]);
  });

  it("offers everything when no subnet matches", () => {
    expect(addressesFor("172.20.0.4", local)).toEqual(["192.168.1.25", "10.0.0.5"]);
  });

  it("leaves out loopback, IPv6 and container bridges", () => {
    const found = lanAddresses({
      lo: [{ address: "127.0.0.1", netmask: "255.0.0.0", family: "IPv4", internal: true, mac: "", cidr: null }],
      docker0: [{ address: "172.17.0.1", netmask: "255.255.0.0", family: "IPv4", internal: false, mac: "", cidr: null }],
      eth0: [
        { address: "192.168.1.25", netmask: "255.255.255.0", family: "IPv4", internal: false, mac: "", cidr: null },
        { address: "fe80::1", netmask: "ffff::", family: "IPv6", internal: false, mac: "", cidr: null, scopeid: 2 },
      ],
    });
    expect(found.map((entry) => entry.address)).toEqual(["192.168.1.25"]);
  });
});

describe("configuration", () => {
  it("puts the public URLs in the TXT record, without credentials or trailing slashes", () => {
    expect(
      fetchTxtRecord({ FETCH_API_PUBLIC: "https://fetch.example.com/api/", FETCH_WEB_PUBLIC: "http://user:pw@nas:7002" })
    ).toEqual({ v: "1", api: "https://fetch.example.com/api" });
  });

  it("names the instance after the host unless told otherwise", () => {
    expect(mdnsConfigFromEnv({}, "nas.lan").instance).toBe("Fetch (nas)");
    expect(mdnsConfigFromEnv({ FETCH_MDNS_NAME: "Living room" }, "nas").instance).toBe("Living room");
    expect(mdnsConfigFromEnv({ PORT: "7100" }, "nas").port).toBe(7100);
    expect(mdnsConfigFromEnv({ PORT: "7100", FETCH_MDNS_PORT: "443" }, "nas").port).toBe(443);
    expect(() => mdnsConfigFromEnv({ FETCH_MDNS_PORT: "nope" }, "nas")).toThrow();
  });

  it("is off unless switched on", () => {
    expect(isMdnsEnabled({})).toBe(false);
    expect(isMdnsEnabled({ FETCH_MDNS: "true" })).toBe(true);
    expect(isMdnsEnabled({ FETCH_MDNS: "0" })).toBe(false);
  });
});
