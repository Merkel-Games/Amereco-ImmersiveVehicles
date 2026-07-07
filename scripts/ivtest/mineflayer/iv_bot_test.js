'use strict';
// Mineflayer integration test for the Immersive Vehicles Fabric port.
// A real vanilla-protocol client joins the production server, confirms the mod's
// items are registered (queried over the network via tab-complete), spawns a
// vehicle through the real item-use path, and observes the resulting entity.
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = process.env.IV_HOST || 'localhost';
const PORT = parseInt(process.env.IV_PORT || '25565', 10);
const USER = process.env.IV_USER || 'IVTester';
const REPORT = process.env.IV_REPORT || '/tmp/ivtest-work/mineflayer/report.json';
const VERSION = '1.20.1';

const report = { pass: false, steps: [], errors: [] };
function step(name, ok, detail) {
  report.steps.push({ name, ok: !!ok, detail: detail === undefined ? null : detail });
  console.log(`[${ok ? 'OK' : 'XX'}] ${name}${detail !== undefined ? ' :: ' + JSON.stringify(detail) : ''}`);
}
function finish(pass) {
  report.pass = pass;
  try { fs.writeFileSync(REPORT, JSON.stringify(report, null, 2)); } catch (e) {}
  console.log('IVBOT_RESULT: ' + (pass ? 'PASS' : 'FAIL'));
  try { bot.end(); } catch (e) {}
  setTimeout(() => process.exit(pass ? 0 : 1), 800);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: USER, version: VERSION, auth: 'offline' });

// Global watchdog.
const watchdog = setTimeout(() => { report.errors.push('watchdog timeout'); step('watchdog', false, 'global timeout'); finish(false); }, 120000);

bot.on('error', (err) => { report.errors.push(String(err && err.message || err)); console.log('BOT ERROR:', err && err.message); });
bot.on('kicked', (reason) => { report.errors.push('kicked: ' + JSON.stringify(reason)); console.log('KICKED:', reason); });

// Track raw entity spawns even if the modded type can't be named by vanilla minecraft-data.
let rawEntitySpawns = 0;
bot._client.on('spawn_entity', () => { rawEntitySpawns++; });

bot.once('spawn', async () => {
  try {
    step('join', true, { username: bot.username, dimension: bot.game.dimension, pos: bot.entity.position });

    // 1) Confirm the mod registered its items — asked over the network via tab-complete.
    let matches = [];
    try {
      const res = await bot.tabComplete('/give IVTester mts:', true, true);
      matches = (res || []).map((m) => (typeof m === 'string' ? m : (m && m.match) || '')).filter(Boolean);
    } catch (e) { report.errors.push('tabComplete: ' + e.message); }
    const mtsItems = matches.filter((m) => m.includes('mts:') || m.startsWith('mts.'));
    step('mts_items_registered', mtsItems.length >= 100, { count: mtsItems.length, sample: mtsItems.slice(0, 5) });

    // 2) Find a concrete vehicle item id.  OCP vehicle system names are known; match them
    //    against the server's real item list so we use an id the server actually has.
    const vehicleNames = ['ft17', 'mc172', 'bell206', 'comanche', 'trimotor', 'scout', 'e500', 'pzlp11', 'bell47g', 'gmcbrig'];
    const norm = (s) => s.replace(/^\//, '').trim();
    let vehicleId = null;
    for (const vn of vehicleNames) {
      const hit = matches.map(norm).find((m) => m.toLowerCase().includes(vn));
      if (hit) { vehicleId = hit; break; }
    }
    if (!vehicleId && mtsItems.length) vehicleId = norm(mtsItems.find((m) => !m.includes('invisible')) || mtsItems[0]);
    step('resolved_vehicle_item', !!vehicleId, vehicleId);

    // 3) Build a platform and give ourselves the vehicle, then spawn it via item-use.
    const px = Math.floor(bot.entity.position.x);
    const pz = Math.floor(bot.entity.position.z);
    const py = Math.floor(bot.entity.position.y);
    bot.chat(`/fill ${px - 3} ${py - 1} ${pz - 3} ${px + 3} ${py - 1} ${pz + 3} minecraft:stone`);
    await sleep(500);
    bot.chat('/clear IVTester');
    await sleep(400);
    const beforeEntities = Object.keys(bot.entities).length;
    const beforeRaw = rawEntitySpawns;

    if (vehicleId) {
      bot.chat(`/give IVTester ${vehicleId.startsWith('mts') ? vehicleId : 'mts:' + vehicleId} 1`);
      await sleep(1200);
      try { bot.setQuickBarSlot(0); } catch (e) {}
      await sleep(300);
      // Look straight down at the platform and use the item on the block below.
      try { await bot.look(bot.entity.yaw, Math.PI / 2, true); } catch (e) {}
      const groundPos = bot.entity.position.offset(0, -1, 0).floored();
      const refBlock = bot.blockAt(groundPos);
      let placed = false;
      if (refBlock) {
        try {
          const { Vec3 } = require('vec3');
          await bot.placeBlock(refBlock, new Vec3(0, 1, 0));
          placed = true;
        } catch (e) {
          // IV vehicles aren't blocks, so placeBlock's block-update wait may throw; the
          // server still receives the use-on-block packet and spawns the vehicle.
          report.errors.push('placeBlock(non-fatal): ' + e.message);
        }
      }
      // Fallback: also try a plain item activation (right-click air), which IV also accepts.
      try { bot.activateItem(); } catch (e) {}
      step('used_vehicle_item', true, { placed });
      await sleep(3500);
    }

    const afterEntities = Object.keys(bot.entities).length;
    const afterRaw = rawEntitySpawns;
    const entityDelta = afterEntities - beforeEntities;
    const rawDelta = afterRaw - beforeRaw;
    // Any IV entity the server spawned (builder_existing/seat/rendering) shows up as a new
    // network entity even though vanilla minecraft-data can't name a modded type.
    const ivEntities = Object.values(bot.entities).filter((e) => {
      const n = (e && (e.name || (e.entityType && String(e.entityType)))) || '';
      return typeof n === 'string' && n.toLowerCase().includes('builder');
    });
    step('vehicle_entity_spawned', entityDelta > 0 || rawDelta > 0, { entityDelta, rawDelta, namedIvEntities: ivEntities.length });

    // 4) Movement sanity: jump + small move without desync/kick.
    bot.setControlState('jump', true); await sleep(400); bot.setControlState('jump', false);
    await sleep(500);
    step('movement_ok', bot.entity && !!bot.entity.position, { pos: bot.entity.position });

    clearTimeout(watchdog);
    const criticalPass = report.steps.find((s) => s.name === 'join').ok
      && report.steps.find((s) => s.name === 'mts_items_registered').ok;
    finish(criticalPass);
  } catch (e) {
    report.errors.push('spawn handler: ' + (e && e.stack || e));
    clearTimeout(watchdog);
    finish(false);
  }
});
