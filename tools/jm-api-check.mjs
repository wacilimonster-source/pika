#!/usr/bin/env node
/**
 * 禁漫移动端 API 可用性验证工具
 *
 * 用法（需要 HTTP 代理时）：
 *   NODE_USE_ENV_PROXY=1 HTTPS_PROXY=http://127.0.0.1:7897 node tools/jm-api-check.mjs
 * 不走代理：
 *   node tools/jm-api-check.mjs
 *
 * 说明：
 *  - 禁漫移动端 API 路径 **没有 /api/v3 前缀**，直接是 /categories、/search 等
 *  - 每个请求必须带签名 header：token=md5(ts+SECRET)，tokenparam="{ts},{VER}"
 *  - 响应 data 默认为 base64 + AES-256-ECB 密文，key=md5(ts+SECRET)
 *  - /setting 的 data 是明文对象，需类型判断（脚本已处理）
 */
import crypto from 'node:crypto';

const SECRET = '185Hcomic3PAPP7R';        // APP_TOKEN_SECRET / APP_DATA_SECRET
const SECRET_SCRAMBLE = '18comicAPPContent'; // 仅 /chapter_view_template 使用
const VER = process.env.JM_VER || '2.1.5';   // 可从 /setting 的 jm3_version 动态取

const API_DOMAINS = [
  'www.cdngwc.cc',
  'www.cdnhjk.net',
  'www.cdngwc.net',
  'www.cdngwc.club',
];
const IMAGE_DOMAINS = [
  'cdn-msp.jmapiproxy1.cc',
  'cdn-msp.jmapiproxy2.cc',
  'cdn-msp2.jmapiproxy2.cc',
  'cdn-msp3.jmapiproxy2.cc',
  'cdn-msp.jmapinodeudzn.net',
  'cdn-msp3.jmapinodeudzn.net',
];

const md5hex = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');

function sign(secret = SECRET) {
  const ts = Math.floor(Date.now() / 1000);
  return { ts, token: md5hex(`${ts}${secret}`), tokenparam: `${ts},${VER}` };
}

function decrypt(b64, ts, secret = SECRET) {
  try {
    const key = Buffer.from(md5hex(`${ts}${secret}`), 'utf8'); // 32 hex chars -> AES-256
    const d = crypto.createDecipheriv('aes-256-ecb', key, null);
    d.setAutoPadding(false);
    const out = Buffer.concat([d.update(Buffer.from(b64, 'base64')), d.final()]);
    const pad = out[out.length - 1];
    return out.subarray(0, out.length - pad).toString('utf8');
  } catch (e) {
    return `[解密失败: ${e.message}]`;
  }
}

const BASE_HEADERS = {
  'Accept-Encoding': 'gzip, deflate',
  'user-agent':
    'Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36',
};

let domainIdx = 0;
async function api(path, { secret = SECRET, timeout = 40000, raw = false } = {}) {
  let lastErr = null;
  for (let i = 0; i < API_DOMAINS.length; i++) {
    const host = API_DOMAINS[(domainIdx + i) % API_DOMAINS.length];
    const { ts, token, tokenparam } = sign(secret);
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), timeout);
    try {
      const res = await fetch(`https://${host}${path}`, {
        headers: { ...BASE_HEADERS, token, tokenparam },
        signal: ac.signal,
      });
      const text = await res.text();
      if (raw) return { ok: true, host, text };
      let json = null;
      try { json = JSON.parse(text); } catch { return { ok: false, host, error: '非 JSON 响应', raw: text.slice(0, 120) }; }
      let data = json.data;
      if (typeof data === 'string' && data.length) {
        const plain = decrypt(data, ts, secret);
        try { data = plain ? JSON.parse(plain) : plain; } catch { data = plain; }
      }
      domainIdx = (domainIdx + i) % API_DOMAINS.length;
      return { ok: true, host, code: json.code, errorMsg: json.errorMsg, data };
    } catch (e) {
      lastErr = e.cause?.message || e.message;
    } finally {
      clearTimeout(timer);
    }
  }
  return { ok: false, error: lastErr };
}

async function imageInfo(photoId, filename, cdn = IMAGE_DOMAINS[0]) {
  const url = `https://${cdn}/media/photos/${photoId}/${filename}`;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 40000);
  try {
    const res = await fetch(url, {
      headers: {
        ...BASE_HEADERS,
        Accept: 'image/avif,image/webp,image/apng,*/*;q=0.8',
        'X-Requested-With': 'com.JMComic3.app',
        Referer: `https://${API_DOMAINS[0]}`,
      },
      signal: ac.signal,
    });
    const buf = Buffer.from(await res.arrayBuffer());
    let dim = '?';
    if (buf.slice(0, 4).toString() === 'RIFF' && buf.slice(8, 12).toString() === 'WEBP') {
      const fmt = buf.slice(12, 16).toString();
      if (fmt === 'VP8 ') {
        const o = buf.indexOf(Buffer.from([0x9d, 0x01, 0x2a]));
        if (o >= 0) dim = `${buf.readUInt16LE(o + 3) & 0x3fff}x${buf.readUInt16LE(o + 5) & 0x3fff}`;
      } else if (fmt === 'VP8X') {
        dim = `${1 + buf.readUIntLE(24, 3)}x${1 + buf.readUIntLE(27, 3)}`;
      }
    }
    return { status: res.status, size: buf.length, dim };
  } catch (e) {
    return { status: 0, error: e.cause?.message || e.message };
  } finally {
    clearTimeout(timer);
  }
}

async function scrambleId(photoId) {
  const r = await api(
    `/chapter_view_template?id=${photoId}&mode=vertical&page=0&app_img_shunt=1&express=off&v=${Math.floor(Date.now() / 1000)}`,
    { secret: SECRET_SCRAMBLE, raw: true },
  );
  if (!r.ok) return null;
  const m = r.text.match(/var scramble_id = (\d+);/);
  return m ? Number(m[1]) : null;
}

// ==================== 验证流程 ====================
const pad = (s, n) => String(s).padEnd(n);
const pass = (b) => (b ? 'OK  ' : 'FAIL');

console.log('禁漫 API 验证  代理=' + (process.env.HTTPS_PROXY || '(无)') + '  ver=' + VER);
console.log('='.repeat(72));

console.log('\n[1] 免登录核心端点');
const core = [
  ['/setting', '/setting'],
  ['/categories', '/categories'],
  ['/categories/filter', '/categories/filter?page=1&o=mr&t=a'],
  ['/search', '/search?search_query=' + encodeURIComponent('同人') + '&page=1&main_tag=0&o=mr&t=a'],
  ['/album', '/album?id=422866'],
  ['/chapter', '/chapter?id=422866'],
  ['/forum', '/forum?mode=all&page=1&aid=422866'],
  ['/week', '/week'],
];
for (const [name, path] of core) {
  const r = await api(path);
  const ok = r.ok && r.code === 200 && r.data && (!Array.isArray(r.data) || true);
  const brief = r.ok
    ? typeof r.data === 'object' && !Array.isArray(r.data)
      ? 'keys=' + Object.keys(r.data).slice(0, 6).join(',')
      : Array.isArray(r.data) ? 'array' : String(r.data).slice(0, 40)
    : r.error;
  console.log(`${pass(ok)} ${pad(name, 22)} code=${r.code ?? '-'} ${r.errorMsg ?? ''} ${brief}`);
}

console.log('\n[2] 排行榜（o=mv_t / mv_w / mv_m）');
for (const [label, o] of [['日榜', 'mv_t'], ['周榜', 'mv_w'], ['月榜', 'mv_m']]) {
  const r = await api(`/categories/filter?page=1&o=${o}&t=a`);
  console.log(`${pass(r.ok && r.code === 200)} ${pad(label, 6)} total=${r.data?.total ?? '-'} 首条=${(r.data?.content?.[0]?.name || '').slice(0, 24)}`);
}

console.log('\n[3] 分类 c= 参数（应返回对应分类名）');
for (const c of ['doujin', 'single', 'short', 'another', 'hanman']) {
  const r = await api(`/categories/filter?page=1&c=${c}&o=mr&t=a`);
  const titles = [...new Set((r.data?.content || []).slice(0, 5).map((x) => x?.category?.title))];
  console.log(`${pad('c=' + c, 14)} 返回分类=${JSON.stringify(titles)}`);
}

console.log('\n[4] 时间参数 t=（实测应全部相同，即参数无效）');
for (const t of ['a', 't', 'w', 'm']) {
  const r = await api(`/categories/filter?page=1&o=mr&t=${t}`);
  console.log(`${pad('t=' + t, 6)} 前5日期=${JSON.stringify((r.data?.content || []).slice(0, 5).map((x) => x?.adddate))}`);
}

console.log('\n[5] 图片 + scramble_id 抽样');
const list = await api('/categories/filter?page=1&o=mr&t=a');
const ids = (list.data?.content || []).slice(0, 3).map((x) => x.id);
ids.push(422866, 300000, 50000); // 2023 / 2021 / 2018 老本子
const dist = {};
for (const id of ids) {
  const c = await api(`/chapter?id=${id}`);
  const imgs = c.data?.images || [];
  const f = imgs[0];
  const sid = await scrambleId(id);
  if (sid) dist[sid] = (dist[sid] || 0) + 1;
  const img = f ? await imageInfo(id, f) : { status: 0 };
  console.log(
    `${pass(img.status === 200)} album ${pad(id, 9)} 图数=${pad(imgs.length, 4)} scramble_id=${pad(sid ?? '-', 7)} HTTP ${img.status} ${img.dim ?? ''} ${img.size ? (img.size / 1024).toFixed(0) + 'KB' : ''}`,
  );
}
console.log('scramble_id 分布: ' + JSON.stringify(dist) + '  (220980=不分割, 268850/421926=需解码)');

console.log('\n[6] 封面图');
for (const p of [`/media/albums/${ids[0]}.jpg`, `/media/albums/${ids[0]}_3x4.jpg`, `/media/albums/${ids[0]}.webp`]) {
  const ac = new AbortController(); const timer = setTimeout(() => ac.abort(), 30000);
  try {
    const r = await fetch(`https://${IMAGE_DOMAINS[0]}${p}`, { headers: BASE_HEADERS, signal: ac.signal });
    console.log(`${pass(r.status === 200)} ${pad(p, 36)} HTTP ${r.status}`);
  } catch (e) { console.log(`FAIL ${pad(p, 36)} ${e.cause?.message || e.message}`); }
  finally { clearTimeout(timer); }
}

console.log('\n[7] 需登录端点（应返回 401，说明端点存在）');
for (const [name, path] of [
  ['收藏夹 GET', '/favorite?page=1&folder_id=0&o=mr'],
  ['浏览历史', '/watch_list?page=1'],
  ['签到', '/daily_chk'],
]) {
  const r = await api(path);
  console.log(`${pad(name, 12)} code=${r.code} ${r.errorMsg ?? ''} data=${JSON.stringify(r.data).slice(0, 50)}`);
}

console.log('\n[8] 不存在的端点（应返回 Not legal.xxx）');
for (const p of ['/hot_search', '/random', '/user_profile', '/tags', '/api/v3/categories']) {
  const r = await api(p);
  console.log(`${pad(p, 22)} code=${r.code} ${r.errorMsg ?? ''}`);
}

console.log('\n' + '='.repeat(72));
console.log('完成。若大量 FAIL 且 error 为网络错误，检查代理是否可用。');
