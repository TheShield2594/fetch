/**
 * A small QR code encoder, for the one QR code the website draws: the
 * `otpauth://` URI an authenticator app scans when two-factor sign-in is set up
 * (Settings → Account). About 120 characters of ASCII, so this covers byte mode
 * at error-correction level M, versions 1 to 9 (up to 180 bytes), and nothing
 * more. It follows ISO/IEC 18004 the way Project Nayuki's reference encoder
 * lays it out.
 *
 * The result is a grid of modules, `true` for dark; drawing it is the caller's.
 */

// Level M, versions 0–9 (index 0 unused).
const ECC_CODEWORDS_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22];
const NUM_ERROR_CORRECTION_BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5];
const MAX_VERSION = 9;
/** Level M's format bits. */
const ECC_FORMAT_BITS = 0;

const getBit = (value: number, index: number): boolean => ((value >>> index) & 1) !== 0;

const numRawDataModules = (version: number): number => {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const numAlign = Math.floor(version / 7) + 2;
    result -= (25 * numAlign - 10) * numAlign - 55;
    if (version >= 7) result -= 36;
  }
  return result;
};

const numDataCodewords = (version: number): number =>
  Math.floor(numRawDataModules(version) / 8) -
  ECC_CODEWORDS_PER_BLOCK[version]! * NUM_ERROR_CORRECTION_BLOCKS[version]!;

// ─── Reed–Solomon over GF(2^8), polynomial 0x11D ───

const gfMultiply = (x: number, y: number): number => {
  let z = 0;
  for (let i = 7; i >= 0; i -= 1) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
};

const rsDivisor = (degree: number): number[] => {
  const result = new Array<number>(degree).fill(0);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i += 1) {
    for (let j = 0; j < result.length; j += 1) {
      result[j] = gfMultiply(result[j]!, root);
      if (j + 1 < result.length) result[j]! ^= result[j + 1]!;
    }
    root = gfMultiply(root, 0x02);
  }
  return result;
};

const rsRemainder = (data: number[], divisor: number[]): number[] => {
  const result = divisor.map(() => 0);
  for (const byte of data) {
    const factor = byte ^ result.shift()!;
    result.push(0);
    divisor.forEach((coef, i) => {
      result[i]! ^= gfMultiply(coef, factor);
    });
  }
  return result;
};

/** Splits the data into blocks, adds each block's error correction, and interleaves them. */
const addEccAndInterleave = (data: number[], version: number): number[] => {
  const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[version]!;
  const blockEccLen = ECC_CODEWORDS_PER_BLOCK[version]!;
  const rawCodewords = Math.floor(numRawDataModules(version) / 8);
  const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
  const shortBlockLen = Math.floor(rawCodewords / numBlocks);

  const divisor = rsDivisor(blockEccLen);
  const blocks: number[][] = [];
  for (let i = 0, k = 0; i < numBlocks; i += 1) {
    const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
    k += dat.length;
    const ecc = rsRemainder(dat, divisor);
    if (i < numShortBlocks) dat.push(0);
    blocks.push(dat.concat(ecc));
  }

  const result: number[] = [];
  for (let i = 0; i < blocks[0]!.length; i += 1) {
    blocks.forEach((block, j) => {
      // Skip the padding byte added to short blocks.
      if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) result.push(block[i]!);
    });
  }
  return result;
};

/** The data codewords: byte mode, the length, the bytes, a terminator and padding. */
const encodeData = (bytes: Uint8Array, version: number): number[] => {
  const bits: number[] = [];
  const append = (value: number, length: number) => {
    for (let i = length - 1; i >= 0; i -= 1) bits.push((value >>> i) & 1);
  };
  append(0b0100, 4);
  // An 8-bit count for versions 1–9.
  append(bytes.length, 8);
  for (const byte of bytes) append(byte, 8);

  const capacityBits = numDataCodewords(version) * 8;
  append(0, Math.min(4, capacityBits - bits.length));
  append(0, (8 - (bits.length % 8)) % 8);
  for (let pad = 0xec; bits.length < capacityBits; pad ^= 0xec ^ 0x11) append(pad, 8);

  const codewords: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    codewords.push(bits.slice(i, i + 8).reduce((acc, bit) => (acc << 1) | bit, 0));
  }
  return codewords;
};

class Grid {
  readonly size: number;
  readonly modules: boolean[][];
  readonly isFunction: boolean[][];

  constructor(readonly version: number) {
    this.size = version * 4 + 17;
    this.modules = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
    this.isFunction = Array.from({ length: this.size }, () => new Array<boolean>(this.size).fill(false));
  }

  setFunction(x: number, y: number, dark: boolean) {
    this.modules[y]![x] = dark;
    this.isFunction[y]![x] = true;
  }

  alignmentPositions(): number[] {
    if (this.version === 1) return [];
    const numAlign = Math.floor(this.version / 7) + 2;
    const step = Math.floor((this.version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4)) * 2;
    const result = [6];
    for (let pos = this.size - 7; result.length < numAlign; pos -= step) result.splice(1, 0, pos);
    return result;
  }

  drawFunctionPatterns() {
    for (let i = 0; i < this.size; i += 1) {
      this.setFunction(6, i, i % 2 === 0);
      this.setFunction(i, 6, i % 2 === 0);
    }
    this.drawFinder(3, 3);
    this.drawFinder(this.size - 4, 3);
    this.drawFinder(3, this.size - 4);

    const positions = this.alignmentPositions();
    const n = positions.length;
    for (let i = 0; i < n; i += 1) {
      for (let j = 0; j < n; j += 1) {
        // Not where the finders are.
        if ((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0)) continue;
        this.drawAlignment(positions[i]!, positions[j]!);
      }
    }
    // Reserved now, written for real once the mask is chosen.
    this.drawFormatBits(0);
    this.drawVersion();
  }

  drawFinder(x: number, y: number) {
    for (let dy = -4; dy <= 4; dy += 1) {
      for (let dx = -4; dx <= 4; dx += 1) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy));
        const xx = x + dx;
        const yy = y + dy;
        if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) this.setFunction(xx, yy, dist !== 2 && dist !== 4);
      }
    }
  }

  drawAlignment(x: number, y: number) {
    for (let dy = -2; dy <= 2; dy += 1) {
      for (let dx = -2; dx <= 2; dx += 1) this.setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }

  drawFormatBits(mask: number) {
    const data = (ECC_FORMAT_BITS << 3) | mask;
    let rem = data;
    for (let i = 0; i < 10; i += 1) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;

    for (let i = 0; i <= 5; i += 1) this.setFunction(8, i, getBit(bits, i));
    this.setFunction(8, 7, getBit(bits, 6));
    this.setFunction(8, 8, getBit(bits, 7));
    this.setFunction(7, 8, getBit(bits, 8));
    for (let i = 9; i < 15; i += 1) this.setFunction(14 - i, 8, getBit(bits, i));

    for (let i = 0; i < 8; i += 1) this.setFunction(this.size - 1 - i, 8, getBit(bits, i));
    for (let i = 8; i < 15; i += 1) this.setFunction(8, this.size - 15 + i, getBit(bits, i));
    // The dark module.
    this.setFunction(8, this.size - 8, true);
  }

  drawVersion() {
    if (this.version < 7) return;
    let rem = this.version;
    for (let i = 0; i < 12; i += 1) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (this.version << 12) | rem;
    for (let i = 0; i < 18; i += 1) {
      const bit = getBit(bits, i);
      const a = this.size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      this.setFunction(a, b, bit);
      this.setFunction(b, a, bit);
    }
  }

  drawCodewords(data: number[]) {
    let i = 0;
    for (let right = this.size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < this.size; vert += 1) {
        for (let j = 0; j < 2; j += 1) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? this.size - 1 - vert : vert;
          if (!this.isFunction[y]![x] && i < data.length * 8) {
            this.modules[y]![x] = getBit(data[i >>> 3]!, 7 - (i & 7));
            i += 1;
          }
        }
      }
    }
  }

  applyMask(mask: number) {
    for (let y = 0; y < this.size; y += 1) {
      for (let x = 0; x < this.size; x += 1) {
        let invert: boolean;
        switch (mask) {
          case 0: invert = (x + y) % 2 === 0; break;
          case 1: invert = y % 2 === 0; break;
          case 2: invert = x % 3 === 0; break;
          case 3: invert = (x + y) % 3 === 0; break;
          case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
          case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break;
          case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
          default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; break;
        }
        if (!this.isFunction[y]![x] && invert) this.modules[y]![x] = !this.modules[y]![x];
      }
    }
  }

  /** The standard's penalty score, which picks the mask that scans most reliably. */
  penalty(): number {
    let result = 0;
    const size = this.size;
    const at = (x: number, y: number) => this.modules[y]![x]!;
    const line = (get: (i: number) => boolean) => {
      let score = 0;
      let run = 1;
      for (let i = 1; i <= size; i += 1) {
        if (i < size && get(i) === get(i - 1)) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      // Finder-like 1:1:3:1:1 with four light modules on one side.
      for (let i = 0; i + 10 < size; i += 1) {
        const p = Array.from({ length: 11 }, (_, k) => get(i + k));
        const core = p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6];
        const before = core && !p[7] && !p[8] && !p[9] && !p[10];
        const coreAfter = !p[0] && !p[1] && !p[2] && !p[3] && p[4] && !p[5] && p[6] && p[7] && p[8] && !p[9] && p[10];
        if (before || coreAfter) score += 40;
      }
      return score;
    };
    for (let y = 0; y < size; y += 1) result += line((x) => at(x, y));
    for (let x = 0; x < size; x += 1) result += line((y) => at(x, y));
    for (let y = 0; y < size - 1; y += 1) {
      for (let x = 0; x < size - 1; x += 1) {
        const c = at(x, y);
        if (c === at(x + 1, y) && c === at(x, y + 1) && c === at(x + 1, y + 1)) result += 3;
      }
    }
    let dark = 0;
    for (const row of this.modules) for (const cell of row) if (cell) dark += 1;
    const total = size * size;
    const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    result += Math.max(0, k) * 10;
    return result;
  }
}

/** The modules of a QR code for `text`, row by row, `true` for dark. Throws if it is too long. */
export function encodeQr(text: string, forceMask?: number): boolean[][] {
  const bytes = new TextEncoder().encode(text);
  let version = 1;
  while (version <= MAX_VERSION && 4 + 8 + bytes.length * 8 > numDataCodewords(version) * 8) version += 1;
  if (version > MAX_VERSION) throw new Error("Too long for a QR code here");

  const codewords = addEccAndInterleave(encodeData(bytes, version), version);
  let best: Grid | null = null;
  let bestPenalty = Infinity;
  for (let mask = 0; mask < 8; mask += 1) {
    if (forceMask !== undefined && mask !== forceMask) continue;
    const grid = new Grid(version);
    grid.drawFunctionPatterns();
    grid.drawCodewords(codewords);
    grid.applyMask(mask);
    grid.drawFormatBits(mask);
    const penalty = grid.penalty();
    if (penalty < bestPenalty) {
      best = grid;
      bestPenalty = penalty;
    }
  }
  return best!.modules;
}

/** One SVG path drawing every dark module, with a four-module quiet zone around them. */
export function qrSvgPath(modules: boolean[][], quietZone = 4): { path: string; size: number } {
  let path = "";
  modules.forEach((row, y) => {
    row.forEach((dark, x) => {
      if (dark) path += `M${x + quietZone} ${y + quietZone}h1v1h-1z`;
    });
  });
  return { path, size: modules.length + quietZone * 2 };
}
