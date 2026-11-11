/**
 * TVs signed in to this install by pairing: the code a TV shows is confirmed
 * here, and each paired TV can be revoked on its own.
 */

import { request } from "./client";
import { runtimeConfig } from "./runtime";
import type { Device } from "./types";

/**
 * Where this browser reaches the API and the website, absolute. A code the TV
 * got from the pairing hub is handed to the API with these, so the TV is told
 * the addresses that actually work rather than whatever the server was
 * configured with.
 */
const pairingAddresses = () => {
  const origin = window.location.origin;
  let apiBase: string | undefined;
  try {
    apiBase = new URL(runtimeConfig.getApiBase(), `${origin}/`).toString().replace(/\/+$/, "");
  } catch {
    apiBase = undefined;
  }
  return { apiBase, webBase: origin };
};

export const devicesApi = {
  getDevices() {
    return request<{ devices: Device[] }>("/devices");
  },
  confirmPairing(code: string, name?: string) {
    return request<{ deviceName: string }>("/devices/pair/confirm", {
      method: "POST",
      body: JSON.stringify({ code, ...(name ? { name } : {}), ...pairingAddresses() }),
    });
  },
  renameDevice(deviceId: string, name: string) {
    return request<{ device: Device }>(`/devices/${encodeURIComponent(deviceId)}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    });
  },
  revokeDevice(deviceId: string) {
    return request<void>(`/devices/${encodeURIComponent(deviceId)}`, { method: "DELETE" });
  },
};
