// Simulation automatique de tous les duels (lancée par GitHub à chaque mise à jour du jeu).
// Ouvre le jeu dans un navigateur invisible, fait COMBATS combats par paire de persos (la moitié de chaque côté)
// et écrit les résultats dans stats.json, que la page stats.html affiche.
// Lancement : node outils/simulation.js [combats par paire]
const http = require("http"), fs = require("fs"), path = require("path");
const { chromium } = require("playwright");

const RACINE = path.join(__dirname, "..");
const COMBATS = +process.argv[2] || 300; // par paire de persos
const PAGES = 4; // navigateurs en parallèle (un par cœur)
const TYPES = { ".html": "text/html", ".json": "application/json", ".js": "text/javascript", ".png": "image/png", ".webp": "image/webp" };

// petit serveur de fichiers pour servir le jeu
const serveur = http.createServer((req, res) => {
  const f = path.join(RACINE, decodeURIComponent(req.url.split("?")[0]));
  if (!f.startsWith(RACINE) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(f)] || "application/octet-stream" });
  fs.createReadStream(f).pipe(res);
});

(async () => {
  await new Promise(r => serveur.listen(0, r));
  const url = `http://localhost:${serveur.address().port}/index.html`;
  const navigateur = await chromium.launch();
  const ouvrir = async () => { const p = await navigateur.newPage(); await p.goto(url); await p.waitForTimeout(8000); await p.evaluate(() => { sonsActifs = false; }); return p; };
  const premiere = await ouvrir();
  const { ids, noms, version } = await premiere.evaluate(() => ({ ids: Object.keys(CHARS), noms: Object.keys(CHARS).map(i => CHARS[i].nom), version: VERSION }));
  // chaque paire (a, c) dans un ordre, puis l'autre : a à gauche la moitié du temps
  const paires = [];
  for (const a of ids) for (const c of ids) if (a < c) paires.push([a, c]);
  const pages = [premiere, ...await Promise.all(Array.from({ length: PAGES - 1 }, ouvrir))];
  const resultats = {};
  let fait = 0;
  await Promise.all(pages.map(async (p, k) => {
    for (let i = k; i < paires.length; i += PAGES) {
      const [a, c] = paires[i];
      resultats[a + "|" + c] = await p.evaluate(([a, c, n]) => {
        const r = { a: 0, c: 0, nul: 0 };
        for (let k = 0; k < n; k++) {
          const gauche = k % 2 === 0; choix[0] = gauche ? a : c; choix[1] = gauche ? c : a; nouveauJeu(true);
          let t = 0; while (jeu.running && t < 180) { maj(1 / 60); t += 1 / 60; }
          const fa = jeu.f[gauche ? 0 : 1], fc = jeu.f[gauche ? 1 : 0];
          if (jeu.running) r.nul++; else if (fa.hp > 0 && fc.hp <= 0) r.a++; else if (fc.hp > 0 && fa.hp <= 0) r.c++; else r.nul++;
        }
        return r;
      }, [a, c, COMBATS]);
      if (++fait % 20 === 0) console.log(`${fait} / ${paires.length} paires`);
    }
  }));
  await navigateur.close(); serveur.close();
  // pourcentages (une égalité compte pour une demi-victoire)
  const duels = {}, total = {};
  for (const a of ids) duels[a] = {};
  for (const [cle, r] of Object.entries(resultats)) {
    const [a, c] = cle.split("|"), n = r.a + r.c + r.nul;
    duels[a][c] = Math.round((r.a + r.nul / 2) / n * 1000) / 10;
    duels[c][a] = Math.round((r.c + r.nul / 2) / n * 1000) / 10;
  }
  for (const a of ids) { const v = Object.values(duels[a]); total[a] = Math.round(v.reduce((s, x) => s + x, 0) / v.length * 10) / 10; }
  const stats = { version, date: new Date().toISOString(), combatsParPaire: COMBATS, ids, noms: Object.fromEntries(ids.map((id, i) => [id, noms[i]])), total, duels };
  fs.writeFileSync(path.join(RACINE, "stats.json"), JSON.stringify(stats, null, 1) + "\n");
  console.log("stats.json écrit pour la version", version);
})().catch(e => { console.error(e); process.exit(1); });
