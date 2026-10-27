import dgram from "node:dgram";
import os from "node:os";

// Advertises this server on the LAN over multicast DNS as `_fetch._tcp`, so the
// TV app can list it in its setup wizard instead of asking for an address.
//
// This is a deliberately small responder rather than a dependency: it answers
// the handful of questions DNS-SD browsers ask (the service type, the instance,
// its host) and announces itself on start and on shutdown. Everything it puts
// on the wire is in the TXT record built by `fetchTxtRecord`: the public API
// and web URLs, which is what the TV pairs against.
//
// Multicast does not cross Docker's default bridge network, so in the stock
// compose file this runs as its own `mdns` service on the host network (see
// `mdns-advertise.ts`); a non-Docker install can set FETCH_MDNS=true on the API.

export const MDNS_ADDRESS = "224.0.0.251";
export const MDNS_PORT = 5353;
export const SERVICE_TYPE = "_fetch._tcp.local";
const SERVICES_META = "_services._dns-sd._udp.local";

const TYPE_A = 1;
const TYPE_PTR = 12;
const TYPE_TXT = 16;
const TYPE_SRV = 33;
const TYPE_ANY = 255;
const CLASS_IN = 1;
const CACHE_FLUSH = 0x8000;
const UNICAST_RESPONSE = 0x8000;

// RFC 6762 §10: host records live two minutes, the rest 75 minutes.
const HOST_TTL = 120;
const SERVICE_TTL = 4500;

export interface MdnsRecord {
  name: string;
  type: number;
  ttl: number;
  /** Unique records (SRV, TXT, A) carry the cache-flush bit; shared ones (PTR) don't. */
  unique: boolean;
  data: Buffer;
}

export interface MdnsQuestion {
  name: string;
  type: number;
  /** The asker wants a unicast reply (the QU bit). */
  unicast: boolean;
}

export interface FetchServiceInfo {
  /** Instance name shown on the TV, e.g. "Fetch (nas)". */
  instance: string;
  /** Host name without `.local`. */
  host: string;
  port: number;
  txt: Record<string, string>;
  addresses: string[];
}

// ─── Wire format ───

function encodeName(name: string): Buffer {
  const parts: Buffer[] = [];
  for (const label of name.split(".").filter(Boolean)) {
    const bytes = Buffer.from(label, "utf8").subarray(0, 63);
    parts.push(Buffer.from([bytes.length]), bytes);
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

/** Reads a possibly compressed name starting at [offset]; returns it and where the field ends. */
function decodeName(packet: Buffer, offset: number): { name: string; end: number } {
  const labels: string[] = [];
  let position = offset;
  let end = -1;
  // Bounded, so a pointer loop in a hostile packet can't spin forever.
  for (let hops = 0; hops < 128; hops++) {
    if (position >= packet.length) throw new Error("Name runs past the packet");
    const length = packet.readUInt8(position);
    if (length === 0) {
      return { name: labels.join("."), end: end === -1 ? position + 1 : end };
    }
    if ((length & 0xc0) === 0xc0) {
      if (position + 1 >= packet.length) throw new Error("Truncated name pointer");
      if (end === -1) end = position + 2;
      position = ((length & 0x3f) << 8) | packet.readUInt8(position + 1);
      continue;
    }
    const start = position + 1;
    if (start + length > packet.length) throw new Error("Label runs past the packet");
    labels.push(packet.toString("utf8", start, start + length));
    position = start + length;
  }
  throw new Error("Too many labels");
}

/**
 * A response packet. [legacy] is for a reply to a one-shot resolver (one not
 * on port 5353): RFC 6762 §6.7 has it echo the query's id and questions.
 */
export function encodeResponse(
  answers: MdnsRecord[],
  additionals: MdnsRecord[] = [],
  legacy?: Pick<MdnsQuery, "id" | "questions" | "questionBytes">
): Buffer {
  const header = Buffer.alloc(12);
  header.writeUInt16BE(legacy?.id ?? 0, 0); // multicast responses always use id 0
  header.writeUInt16BE(0x8400, 2); // response, authoritative
  header.writeUInt16BE(legacy?.questions.length ?? 0, 4);
  header.writeUInt16BE(answers.length, 6);
  header.writeUInt16BE(0, 8);
  header.writeUInt16BE(additionals.length, 10);
  const records = [...answers, ...additionals].map((record) => {
    const fixed = Buffer.alloc(10);
    fixed.writeUInt16BE(record.type, 0);
    fixed.writeUInt16BE(CLASS_IN | (record.unique ? CACHE_FLUSH : 0), 2);
    fixed.writeUInt32BE(record.ttl, 4);
    fixed.writeUInt16BE(record.data.length, 8);
    return Buffer.concat([encodeName(record.name), fixed, record.data]);
  });
  return Buffer.concat([header, legacy?.questionBytes ?? Buffer.alloc(0), ...records]);
}

export interface MdnsQuery {
  id: number;
  questions: MdnsQuestion[];
  /** The question section as sent, for echoing back in a legacy reply. */
  questionBytes: Buffer;
}

/** The questions in a query, or null for anything that isn't one (responses, junk). */
export function parseQuery(packet: Buffer): MdnsQuery | null {
  if (packet.length < 12) return null;
  const flags = packet.readUInt16BE(2);
  if (flags & 0x8000) return null; // a response, not a query
  const count = packet.readUInt16BE(4);
  const questions: MdnsQuestion[] = [];
  let offset = 12;
  try {
    for (let i = 0; i < count; i++) {
      const { name, end } = decodeName(packet, offset);
      if (end + 4 > packet.length) return null;
      const type = packet.readUInt16BE(end);
      const klass = packet.readUInt16BE(end + 2);
      questions.push({ name, type, unicast: (klass & UNICAST_RESPONSE) !== 0 });
      offset = end + 4;
    }
  } catch {
    return null;
  }
  return { id: packet.readUInt16BE(0), questions, questionBytes: packet.subarray(12, offset) };
}

export function encodeTxt(txt: Record<string, string>): Buffer {
  const entries = Object.entries(txt)
    .map(([key, value]) => Buffer.from(`${key}=${value}`, "utf8"))
    // A TXT string is length-prefixed with one byte.
    .filter((entry) => entry.length <= 255)
    .map((entry) => Buffer.concat([Buffer.from([entry.length]), entry]));
  // An empty TXT record is a single empty string.
  return entries.length ? Buffer.concat(entries) : Buffer.from([0]);
}

// ─── Records ───

function instanceName(info: FetchServiceInfo): string {
  // The instance label may contain dots and spaces; only its length is limited.
  return `${info.instance.replace(/\./g, " ").slice(0, 63)}.${SERVICE_TYPE}`;
}

function hostName(info: FetchServiceInfo): string {
  return `${info.host}.local`;
}

export function serviceRecords(info: FetchServiceInfo, ttlOverride?: number) {
  const ttl = (value: number) => ttlOverride ?? value;
  const instance = instanceName(info);
  const srv = Buffer.alloc(6);
  srv.writeUInt16BE(0, 0); // priority
  srv.writeUInt16BE(0, 2); // weight
  srv.writeUInt16BE(info.port, 4);
  const ptr: MdnsRecord = {
    name: SERVICE_TYPE,
    type: TYPE_PTR,
    ttl: ttl(SERVICE_TTL),
    unique: false,
    data: encodeName(instance),
  };
  const meta: MdnsRecord = {
    name: SERVICES_META,
    type: TYPE_PTR,
    ttl: ttl(SERVICE_TTL),
    unique: false,
    data: encodeName(SERVICE_TYPE),
  };
  const srvRecord: MdnsRecord = {
    name: instance,
    type: TYPE_SRV,
    ttl: ttl(HOST_TTL),
    unique: true,
    data: Buffer.concat([srv, encodeName(hostName(info))]),
  };
  const txtRecord: MdnsRecord = {
    name: instance,
    type: TYPE_TXT,
    ttl: ttl(SERVICE_TTL),
    unique: true,
    data: encodeTxt(info.txt),
  };
  const aRecords: MdnsRecord[] = info.addresses.map((address) => ({
    name: hostName(info),
    type: TYPE_A,
    ttl: ttl(HOST_TTL),
    unique: true,
    data: Buffer.from(address.split(".").map(Number)),
  }));
  return { ptr, meta, srv: srvRecord, txt: txtRecord, a: aRecords };
}

/**
 * What to answer for [questions], or null when none of them is about this
 * service. `info.addresses` should already be the ones this asker can reach.
 */
export function answerFor(
  questions: MdnsQuestion[],
  info: FetchServiceInfo
): { answers: MdnsRecord[]; additionals: MdnsRecord[] } | null {
  const records = serviceRecords(info);
  const instance = instanceName(info).toLowerCase();
  const host = hostName(info).toLowerCase();
  const answers = new Set<MdnsRecord>();
  const additionals = new Set<MdnsRecord>();
  const wants = (question: MdnsQuestion, type: number) => question.type === type || question.type === TYPE_ANY;

  for (const question of questions) {
    const name = question.name.toLowerCase();
    if (name === SERVICE_TYPE && wants(question, TYPE_PTR)) {
      answers.add(records.ptr);
      [records.srv, records.txt, ...records.a].forEach((record) => additionals.add(record));
    } else if (name === SERVICES_META && wants(question, TYPE_PTR)) {
      answers.add(records.meta);
    } else if (name === instance) {
      if (wants(question, TYPE_SRV)) answers.add(records.srv);
      if (wants(question, TYPE_TXT)) answers.add(records.txt);
      records.a.forEach((record) => additionals.add(record));
    } else if (name === host && wants(question, TYPE_A)) {
      records.a.forEach((record) => answers.add(record));
    }
  }
  if (answers.size === 0) return null;
  answers.forEach((record) => additionals.delete(record));
  return { answers: [...answers], additionals: [...additionals] };
}

// ─── Addresses ───

export interface LocalAddress {
  name: string;
  address: string;
  netmask: string;
}

// Container and VM bridges: reachable from this host only, never from the TV.
const VIRTUAL_INTERFACE = /^(docker|br-|veth|virbr|cni|flannel|cali|vmnet|vboxnet|tun|tap|zt|tailscale|wg)/i;

export function lanAddresses(
  interfaces: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces()
): LocalAddress[] {
  const all: LocalAddress[] = [];
  for (const [name, entries] of Object.entries(interfaces)) {
    for (const entry of entries ?? []) {
      if (entry.family !== "IPv4" || entry.internal) continue;
      all.push({ name, address: entry.address, netmask: entry.netmask });
    }
  }
  const physical = all.filter((entry) => !VIRTUAL_INTERFACE.test(entry.name));
  return physical.length ? physical : all;
}

function toInt(address: string): number {
  return address.split(".").reduce((value, part) => (value << 8) + Number(part), 0) >>> 0;
}

function sameSubnet(peerValue: number, entry: LocalAddress): boolean {
  const mask = toInt(entry.netmask);
  return (toInt(entry.address) & mask) === (peerValue & mask);
}

/**
 * Whether [peer] is on one of this host's subnets. RFC 6762 §11: only on-link askers get
 * an answer, so a port forward to 5353 can't turn this into a reflector for the internet.
 */
export function isOnLink(peer: string, local: LocalAddress[]): boolean {
  const peerValue = toInt(peer);
  return local.some((entry) => sameSubnet(peerValue, entry));
}

/** The local addresses on the same subnet as [peer], so a reply never offers one it can't reach. */
export function addressesFor(peer: string, local: LocalAddress[]): string[] {
  const peerValue = toInt(peer);
  const onLink = local.filter((entry) => sameSubnet(peerValue, entry));
  return (onLink.length ? onLink : local).map((entry) => entry.address);
}

// ─── Configuration ───

export interface MdnsConfig {
  instance: string;
  port: number;
  txt: Record<string, string>;
}

/** A URL worth putting in the TXT record: http(s) only, no credentials. */
function publicUrl(raw: string | undefined): string | undefined {
  if (!raw?.trim()) return undefined;
  try {
    const url = new URL(raw.trim());
    if (url.protocol !== "http:" && url.protocol !== "https:") return undefined;
    if (url.username || url.password) return undefined;
    return url.toString().replace(/\/+$/, "");
  } catch {
    return undefined;
  }
}

export function fetchTxtRecord(env: NodeJS.ProcessEnv): Record<string, string> {
  const txt: Record<string, string> = { v: "1" };
  const api = publicUrl(env.FETCH_API_PUBLIC);
  const web = publicUrl(env.FETCH_WEB_PUBLIC);
  if (api) txt.api = api;
  if (web) txt.web = web;
  return txt;
}

export function mdnsConfigFromEnv(env: NodeJS.ProcessEnv, hostname = os.hostname()): MdnsConfig {
  const port = Number(env.FETCH_MDNS_PORT ?? env.PORT ?? 7000);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`FETCH_MDNS_PORT must be a port number, got "${env.FETCH_MDNS_PORT ?? env.PORT}"`);
  }
  const shortHost = hostname.split(".")[0] || "fetch";
  return {
    instance: env.FETCH_MDNS_NAME?.trim() || `Fetch (${shortHost})`,
    port,
    txt: fetchTxtRecord(env),
  };
}

export function isMdnsEnabled(env: NodeJS.ProcessEnv): boolean {
  return /^(1|true|yes|on)$/i.test(env.FETCH_MDNS?.trim() ?? "");
}

// ─── Responder ───

interface Logger {
  info: (message: string) => void;
  warn: (message: string) => void;
}

export interface MdnsAdvertiser {
  stop: () => Promise<void>;
}

function hostLabel(hostname: string): string {
  const label = hostname.split(".")[0]?.replace(/[^a-z0-9-]/gi, "-").slice(0, 63);
  return label || "fetch";
}

export async function startMdnsAdvertiser(config: MdnsConfig, log: Logger): Promise<MdnsAdvertiser> {
  const local = lanAddresses();
  if (local.length === 0) {
    log.warn("mDNS: no LAN address found, so nothing is advertised");
    return { stop: async () => {} };
  }
  const info = (addresses: string[]): FetchServiceInfo => ({
    instance: config.instance,
    host: hostLabel(os.hostname()),
    port: config.port,
    txt: config.txt,
    addresses,
  });

  // Node 22.12+ can set SO_REUSEPORT, which a host already running Avahi needs
  // for a second listener on 5353; older ones fall back to SO_REUSEADDR only.
  const options = { type: "udp4", reuseAddr: true, reusePort: true } as dgram.SocketOptions;
  let socket: dgram.Socket;
  try {
    socket = dgram.createSocket(options);
  } catch {
    socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  }

  try {
    await new Promise<void>((resolve, reject) => {
      socket.once("error", reject);
      socket.bind(MDNS_PORT, () => {
        socket.off("error", reject);
        resolve();
      });
    });
  } catch (error) {
    socket.close();
    log.warn(
      `mDNS: couldn't listen on UDP ${MDNS_PORT} (${(error as Error).message}), so the TV won't find this server by itself. ` +
        "See web/README.md, \"Finding the server from the TV\"."
    );
    return { stop: async () => {} };
  }

  socket.setMulticastTTL(255);
  socket.setMulticastLoopback(true);
  for (const entry of local) {
    try {
      socket.addMembership(MDNS_ADDRESS, entry.address);
    } catch {
      // An interface that can't join just won't hear queries.
    }
  }

  const sendTo = (packet: Buffer, address: string, port: number, viaInterface?: string) => {
    try {
      if (viaInterface) socket.setMulticastInterface(viaInterface);
      socket.send(packet, port, address);
    } catch {
      // Sending is best effort; the next query gets another answer.
    }
  };

  const announce = (ttl?: number) => {
    for (const entry of local) {
      const records = serviceRecords(info([entry.address]), ttl);
      const packet = encodeResponse([records.ptr, records.srv, records.txt, ...records.a, records.meta]);
      sendTo(packet, MDNS_ADDRESS, MDNS_PORT, entry.address);
    }
  };

  socket.on("message", (packet, rinfo) => {
    const query = parseQuery(packet);
    if (!query?.questions.length) return;
    if (!isOnLink(rinfo.address, local)) return;
    const addresses = addressesFor(rinfo.address, local);
    const reply = answerFor(query.questions, info(addresses));
    if (!reply) return;
    const response = encodeResponse(reply.answers, reply.additionals);
    if (rinfo.port !== MDNS_PORT) {
      // A one-shot resolver only listens for a direct reply.
      sendTo(encodeResponse(reply.answers, reply.additionals, query), rinfo.address, rinfo.port);
      return;
    }
    if (query.questions.some((question) => question.unicast)) {
      sendTo(response, rinfo.address, rinfo.port);
    } else {
      const viaInterface = local.find((entry) => entry.address === addresses[0])?.address;
      sendTo(response, MDNS_ADDRESS, MDNS_PORT, viaInterface);
    }
  });
  socket.on("error", (error) => log.warn(`mDNS: ${error.message}`));

  // RFC 6762 §8.3: announce at least twice, a second apart.
  announce();
  const second = setTimeout(() => announce(), 1_000);
  second.unref();

  log.info(
    `mDNS: advertising "${config.instance}" as ${SERVICE_TYPE} on port ${config.port} ` +
      `(${local.map((entry) => entry.address).join(", ")})`
  );

  return {
    stop: () =>
      new Promise<void>((resolve) => {
        clearTimeout(second);
        // A goodbye (TTL 0) so browsers drop the server now rather than when it expires.
        announce(0);
        // Give the goodbye a moment to leave before the socket goes.
        setTimeout(() => socket.close(() => resolve()), 100);
      }),
  };
}
