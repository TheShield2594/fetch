// Advertises the Fetch server on the LAN and does nothing else.
//
// Multicast doesn't cross Docker's default bridge network, so the API container
// can't announce itself from where it runs. This entry point runs from the same
// image as its own `mdns` service with `network_mode: host`, which keeps the API
// on the bridge network and gives only this small process the host's interfaces.
// It needs no database and no secrets: just the public URLs it puts in the TXT
// record and the port the API is published on.
import { mdnsConfigFromEnv, startMdnsAdvertiser } from "./lib/mdns.js";

const log = {
  info: (message: string) => console.log(message),
  warn: (message: string) => console.warn(message),
};

const advertiser = await startMdnsAdvertiser(mdnsConfigFromEnv(process.env), log);

let stopping = false;
const stop = async () => {
  if (stopping) return;
  stopping = true;
  await advertiser.stop();
  process.exit(0);
};
process.on("SIGINT", () => void stop());
process.on("SIGTERM", () => void stop());

// Keep the process alive even when nothing could be advertised, so the
// container doesn't restart in a loop; the warning above says why.
setInterval(() => {}, 1 << 30);
