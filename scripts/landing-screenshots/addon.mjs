// A local Stremio addon for the landing-page screenshots: public-domain films
// and Blender open movies only, with placeholder art drawn by make-art.sh, so
// no catalog's (copyrighted) artwork can turn up. No network.
//
//   node addon.mjs <work-dir>     (serves <work-dir>/art/*.jpg and film.mp4)
import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const DIR = path.resolve(process.argv[2] ?? ".");
const PORT = 7171;
const BASE = `http://127.0.0.1:${PORT}`;

const T = [
  ["notld", "Night of the Living Dead", 1968, 96, ["Horror"], "George A. Romero", ["Duane Jones", "Judith O'Dea", "Karl Hardman"], "A group of strangers barricade themselves in a Pennsylvania farmhouse while the recently dead walk the fields outside.", 7.8],
  ["nosferatu", "Nosferatu", 1922, 94, ["Horror", "Fantasy"], "F. W. Murnau", ["Max Schreck", "Gustav von Wangenheim", "Greta Schröder"], "An estate agent travels to the Carpathians to sell a house to Count Orlok, who has an interest in more than property.", 7.9],
  ["general", "The General", 1926, 78, ["Comedy", "Action"], "Buster Keaton", ["Buster Keaton", "Marion Mack", "Glen Cavender"], "A train engineer chases his stolen locomotive, and the woman he loves, across enemy lines.", 8.1],
  ["hgf", "His Girl Friday", 1940, 92, ["Comedy", "Romance"], "Howard Hawks", ["Cary Grant", "Rosalind Russell", "Ralph Bellamy"], "A newspaper editor learns his ex-wife and best reporter is about to remarry, and drags her back for one last story.", 7.8],
  ["bbb", "Big Buck Bunny", 2008, 10, ["Animation", "Comedy"], "Sacha Goedegebure", ["Blender Foundation"], "A large, gentle rabbit is pushed too far by three bullying rodents.", 6.4],
  ["sintel", "Sintel", 2010, 15, ["Animation", "Fantasy"], "Colin Levy", ["Halina Reijn", "Thom Hoffman"], "A lone girl searches the frozen north for the baby dragon she raised.", 7.4],
  ["tos", "Tears of Steel", 2012, 12, ["Sci-Fi", "Short"], "Ian Hubert", ["Derek de Lint", "Sergio Hasselbaink", "Rogier Schippers"], "In a future Amsterdam, a group of scientists try to rescue the world from destructive robots.", 6.0],
  ["spring", "Spring", 2019, 8, ["Animation", "Fantasy"], "Andy Goralczyk", ["Blender Foundation"], "A shepherd girl and her dog face ancient spirits to bring back the spring.", 7.2],
].map(([id, name, year, runtime, genres, director, cast, description, rating]) => ({
  id: `fetchdemo:${id}`,
  type: "movie",
  name,
  poster: `${BASE}/art/poster_${id}.jpg?v=2`,
  background: `${BASE}/art/bg_${id}.jpg?v=2`,
  description,
  releaseInfo: String(year),
  year: String(year),
  runtime: `${runtime} min`,
  genres,
  director: [director],
  cast,
  posterShape: "poster",
}));

const classics = T.slice(0, 4);
const blender = T.slice(4);

const manifest = {
  id: "tv.watchfetch.demo",
  version: "1.0.0",
  name: "Public Domain Classics",
  description: "Public-domain films and Blender open movies.",
  resources: ["catalog", "meta", "stream"],
  types: ["movie"],
  idPrefixes: ["fetchdemo:"],
  catalogs: [
    { type: "movie", id: "classics", name: "Public domain classics" },
    { type: "movie", id: "blender", name: "Blender open movies" },
    { type: "movie", id: "all", name: "Everything" },
  ],
};

function send(res, code, body, type = "application/json") {
  res.writeHead(code, { "Content-Type": type, "Access-Control-Allow-Origin": "*" });
  res.end(typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

http
  .createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, BASE).pathname);
    let m;
    if (url === "/manifest.json") return send(res, 200, manifest);
    if ((m = /^\/catalog\/movie\/(\w+)(?:\/[^/]*)?\.json$/.exec(url))) {
      const list = m[1] === "classics" ? classics : m[1] === "blender" ? blender : T;
      return send(res, 200, { metas: list });
    }
    if ((m = /^\/meta\/movie\/(.+)\.json$/.exec(url))) {
      const meta = T.find((t) => t.id === m[1]);
      return send(res, meta ? 200 : 404, meta ? { meta } : { meta: null });
    }
    if ((m = /^\/stream\/movie\/(.+)\.json$/.exec(url))) {
      const meta = T.find((t) => t.id === m[1]);
      if (!meta) return send(res, 200, { streams: [] });
      return send(res, 200, {
        streams: [
          { name: "Demo\n1080p", title: `${meta.name} (${meta.year}) 1080p WEB H.264 AAC 2.0\n💾 1.4 GB`, url: `${BASE}/film.mp4`, behaviorHints: { filename: `${meta.name}.${meta.year}.1080p.WEB.H264.AAC.mp4`, videoSize: 1503238553 } },
          { name: "Demo\n720p", title: `${meta.name} (${meta.year}) 720p WEB H.264 AAC 2.0\n💾 820 MB`, url: `${BASE}/film.mp4`, behaviorHints: { filename: `${meta.name}.${meta.year}.720p.WEB.H264.AAC.mp4`, videoSize: 859832320 } },
        ],
      });
    }
    if ((m = /^\/art\/([\w.]+\.jpg)$/.exec(url))) {
      const f = path.join(DIR, "art", m[1]);
      if (fs.existsSync(f)) return send(res, 200, fs.readFileSync(f), "image/jpeg");
    }
    if (url === "/film.mp4") {
      // Range support, so mpv can seek to the resume point.
      const f = path.join(DIR, "film.mp4");
      const size = fs.statSync(f).size;
      const range = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
      if (range) {
        const start = Number(range[1]);
        const end = range[2] ? Number(range[2]) : size - 1;
        res.writeHead(206, { "Content-Type": "video/mp4", "Content-Range": `bytes ${start}-${end}/${size}`, "Accept-Ranges": "bytes", "Content-Length": end - start + 1 });
        return fs.createReadStream(f, { start, end }).pipe(res);
      }
      res.writeHead(200, { "Content-Type": "video/mp4", "Content-Length": size, "Accept-Ranges": "bytes" });
      return fs.createReadStream(f).pipe(res);
    }
    send(res, 404, { error: "not found" });
  })
  .listen(PORT, "127.0.0.1", () => console.log(`demo addon on ${BASE}`));
