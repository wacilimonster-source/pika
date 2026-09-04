#!/usr/bin/env node
/**
 * 哔咔（Pika）列表接口「更新时间字段」探测工具
 *
 * 目的：确认各个列表页接口返回的漫画条目里到底有没有 updated_at，
 *      从而判断「列表页展示作品更新时间」是「补 UI」还是「补数据」。
 *
 * 用法：
 *   PICA_EMAIL=xxx PICA_PASSWORD=yyy node tools/pica-api-check.mjs
 *   或复用已有 token：PICA_TOKEN=xxx node tools/pica-api-check.mjs
 *   需要代理时：NODE_USE_ENV_PROXY=1 HTTPS_PROXY=http://127.0.0.1:7897 node tools/pica-api-check.mjs
 *
 * 注意：
 *  - 哔咔走 Cloudflare Bot Management，App 内必须用 HttpsURLConnection + BCJSSE 才能 200，
 *    OkHttp 会被拦。Node 的 fetch 也可能被拦（表现为 403/1023），属预期现象，换代理或换 host 重试。
 *  - 脚本只读：1 次登录 POST + 若干列表 GET，不做任何写操作。
 *  - 请求头/签名与 App 完全一致（见 app/src/main/java/com/pika/core/pica/PicaConfig.kt）。
 */
import crypto from 'node:crypto';

const API_KEY = 'C69BAF41DA5ABD1FFEDC6D2FEA56B';
const SECRET_KEY = '~d}$Q7$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn';
const HOSTS = ['https://picaapi.picacomic.com/', 'https://picaapi.go2778.com/'];
const APP_UUID = crypto.randomUUID().replace(/-/g, '');

const pad = (s, n) => String(s).padEnd(n);
const yes = (b) => (b ? '有' : '无 ');

let hostIdx = 0;
let token = process.env.PICA_TOKEN || '';

function sign(pathForSign, ts, nonce, method) {
  const key = `${pathForSign}${ts}${nonce}${method}${API_KEY}`.toLowerCase();
  return crypto.createHmac('sha256', SECRET_KEY).update(key).digest('hex');
}

async function api(method, path, body) {
  let lastErr = '';
  for (let i = 0; i < HOSTS.length; i++) {
    const host = HOSTS[(hostIdx + i) % HOSTS.length];
    const ts = Math.floor(Date.now() / 1000).toString();
    const nonce = crypto.randomUUID().replace(/-/g, '');
    const headers = {
      accept: 'application/vnd.picacomic.com.v1+json',
      'User-Agent': 'okhttp/3.8.1',
      'Content-Type': 'application/json; charset=UTF-8',
      'api-key': API_KEY,
      'app-build-version': '44',
      'app-platform': 'android',
      'app-uuid': APP_UUID,
      'app-version': '2.2.1.2.3.3',
      'app-channel': '1',
      time: ts,
      nonce,
      signature: sign(path, ts, nonce, method),
      'image-quality': 'original',
    };
    if (token) headers.authorization = token;
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 20000);
    try {
      const res = await fetch(host + path, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: ac.signal,
      });
      const text = await res.text();
      let json = null;
      try { json = JSON.parse(text); } catch { return { ok: false, host, error: '非 JSON: ' + text.slice(0, 100) }; }
      hostIdx = (hostIdx + i) % HOSTS.length;
      return { ok: true, host, http: res.status, code: json.code, message: json.message, data: json.data };
    } catch (e) {
      lastErr = e.cause?.message || e.message;
    } finally {
      clearTimeout(timer);
    }
  }
  return { ok: false, error: lastErr };
}

/** 取列表首条目的字段名集合，并报告 updated_at / created_at 情况 */
function describeList(data) {
  if (!data) return { error: 'data 为空' };
  const docs = Array.isArray(data) ? data : data.docs ?? data.comics ?? null;
  if (!Array.isArray(docs)) {
    return { error: '未识别的列表结构，keys=' + Object.keys(data || {}).join(',') };
  }
  if (docs.length === 0) return { error: '列表为空' };
  const keys = new Set();
  docs.slice(0, 10).forEach((d) => Object.keys(d).forEach((k) => keys.add(k)));
  const first = docs[0];
  return {
    count: docs.length,
    keys: [...keys],
    hasUpdatedAt: 'updated_at' in first,
    hasCreatedAt: 'created_at' in first,
    sample: first.updated_at ?? first.created_at ?? '',
    title: first.title ?? '',
  };
}

console.log('哔咔列表接口 updated_at 探测   代理=' + (process.env.HTTPS_PROXY || '(无)'));
console.log('='.repeat(78));

// ---------- 登录 ----------
if (!token) {
  const email = process.env.PICA_EMAIL;
  const password = process.env.PICA_PASSWORD;
  if (!email || !password) {
    console.log('未提供 PICA_TOKEN，也未设置 PICA_EMAIL / PICA_PASSWORD，无法继续。');
    process.exit(1);
  }
  const r = await api('POST', 'auth/sign-in', { email, password });
  if (r.ok && r.code === 200 && r.data?.token) {
    token = r.data.token;
    console.log(`登录成功 (${r.host})`);
  } else {
    console.log(`登录失败 code=${r.code ?? '-'} ${r.message ?? r.error ?? ''}`);
    console.log('若 code=1023 或 HTTP 403，说明被 Cloudflare 拦截，请挂代理重试（NODE_USE_ENV_PROXY=1）。');
    process.exit(1);
  }
} else {
  console.log('使用 PICA_TOKEN，跳过登录');
}

// ---------- 先取一个漫画 id，供推荐接口使用 ----------
let sampleId = '';
{
  const r = await api('GET', 'comics?page=1&s=dd');
  const d = describeList(r.ok ? r.data?.comics : null);
  if (!d.error) sampleId = r.data.comics.docs[0]._id || '';
}

// ---------- 逐个列表接口探测 ----------
const probes = [
  ['分类浏览 comics', 'GET', 'comics?page=1&s=dd', null, (d) => d.comics],
  ['分类浏览(带 c)', 'GET', 'comics?page=1&s=dd&c=' + encodeURIComponent('嗶咔漢化'), null, (d) => d.comics],
  ['搜索 advanced-search', 'POST', 'comics/advanced-search?page=1', { keyword: '同人', sort: 'dd' }, (d) => d.comics],
  ['排行榜 leaderboard', 'GET', 'comics/leaderboard?tt=H24&ct=VC', null, (d) => ({ docs: d.comics ?? [] })],
  ['随机 comics/random', 'GET', 'comics/random', null, (d) => ({ docs: d.comics ?? [] })],
  ['收藏 users/favourite', 'GET', 'users/favourite?page=1&s=dd', null, (d) => d.comics],
];
if (sampleId) {
  probes.push(['推荐 comics/{id}/recommendation', 'GET', `comics/${sampleId}/recommendation`, null, (d) => ({ docs: d.comics ?? [] })]);
  probes.push(['详情 comics/{id}（对照组）', 'GET', `comics/${sampleId}`, null, (d) => ({ docs: [d.comic ?? {}] })]);
}

console.log('\n接口'.padEnd(34) + '条数  updated_at  created_at  示例值');
console.log('-'.repeat(78));
const results = {};
for (const [name, method, path, body, pick] of probes) {
  const r = await api(method, path, body);
  if (!r.ok) {
    console.log(`${pad(name, 32)} ERROR ${r.error}`);
    results[name] = { error: r.error };
    continue;
  }
  if (r.code !== 200) {
    console.log(`${pad(name, 32)} code=${r.code} ${r.message ?? ''}`);
    results[name] = { error: r.message };
    continue;
  }
  const d = describeList(pick(r.data));
  if (d.error) {
    console.log(`${pad(name, 32)} ${d.error}`);
    results[name] = { error: d.error };
    continue;
  }
  results[name] = d;
  console.log(
    `${pad(name, 32)} ${pad(d.count, 5)} ${yes(d.hasUpdatedAt)}        ${yes(d.hasCreatedAt)}         ${String(d.sample).slice(0, 24)}`,
  );
}

// ---------- 字段全量差异 ----------
console.log('\n各接口首条目完整字段名（用于发现可补充字段）');
console.log('-'.repeat(78));
for (const [name] of probes) {
  const d = results[name];
  if (!d || d.error) continue;
  console.log(`${name}\n   ${d.keys.join(', ')}`);
}

console.log('\n' + '='.repeat(78));
console.log('判读：updated_at=有 → 该列表页数据已具备，UI 已有渲染（ComicGridView 会显示 take(10)）。');
console.log('      updated_at=无 → 服务端不返回，列表页无法直接展示，需另想办法或留空。');
