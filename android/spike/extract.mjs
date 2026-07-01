// ZeroDelay Android — feasibility spike (reference; not production code)
//
// Question this answers: can we get the HLS master-playlist URL of a LIVE
// YouTube stream with no official API and no login? (Everything else in the
// port — ExoPlayer + the catch-up controller — is low-risk; this was the make-
// or-break unknown.)
//
// Finding (2026-07-01): the InnerTube `/youtubei/v1/player` endpoint is now
// gated behind bot attestation (PoToken) for every client we tried — it returns
// "Sign in to confirm you're not a bot" / "The page needs to be reloaded".
// BUT the plain watch page still embeds `ytInitialPlayerResponse`, and for LIVE
// streams the `hlsManifestUrl` there needs no signature deciphering, so it plays
// directly. That is the path this script uses.
//
// Two gotchas found and handled here:
//   1) A consent cookie (SOCS=CAI) + a browser User-Agent are required, else the
//      page is a consent interstitial with no player response.
//   2) The response is not 100% deterministic: sometimes streamingData is absent
//      and the request must be retried (YouTube asks to "reload").
//
// Usage:
//   node extract.mjs                      # resolves @SkyNews/live and extracts
//   node extract.mjs <videoId>            # extract a specific live video id
//   node extract.mjs @ChannelHandle       # resolve a channel's current live

const BROWSER_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36',
    'Accept-Language': 'en',
    Cookie: 'PREF=hl=en; SOCS=CAI', // consent cookie — without it the page is an interstitial
};

const unescapeJson = s => s.replace(/\\u0026/g, '&').replace(/\\\//g, '/');

// Resolve a channel handle's /live to the currently-live videoId.
async function resolveLive(handle) {
    const res = await fetch(`https://www.youtube.com/${handle}/live`, { headers: BROWSER_HEADERS });
    const html = await res.text();
    const canon = html.match(/<link rel="canonical" href="https:\/\/www\.youtube\.com\/watch\?v=([\w-]{11})">/);
    if (!canon) throw new Error(`could not resolve a live videoId for ${handle}`);
    return canon[1];
}

// Fetch the watch page and pull the live HLS manifest URL out of the embedded
// player response. Retries a few times because streamingData can be absent.
async function extractHls(videoId, attempts = 4) {
    for (let i = 1; i <= attempts; i++) {
        const res = await fetch(`https://www.youtube.com/watch?v=${videoId}`, { headers: BROWSER_HEADERS });
        const html = await res.text();
        const status = (html.match(/"playabilityStatus":\{"status":"([^"]+)"/) || [])[1];
        const isLive = /"isLiveNow":true/.test(html);
        const hls = html.match(/"hlsManifestUrl":"([^"]+)"/);
        if (hls) return { videoId, status, isLive, hlsUrl: unescapeJson(hls[1]), attempt: i };
        if (i < attempts) await new Promise(r => setTimeout(r, 800));
        else return { videoId, status, isLive, hlsUrl: null, attempt: i };
    }
}

// Prove the extracted URL is a real, live master playlist with playable media.
async function validate(hlsUrl) {
    const master = await (await fetch(hlsUrl, { headers: { 'User-Agent': BROWSER_HEADERS['User-Agent'] } })).text();
    const variants = master.match(/#EXT-X-STREAM-INF[^\n]*/g) || [];
    const firstVariantUrl = (master.split('\n').find(l => l.startsWith('http')) || '').trim();
    let media = null;
    if (firstVariantUrl) {
        const mt = await (await fetch(firstVariantUrl, { headers: { 'User-Agent': BROWSER_HEADERS['User-Agent'] } })).text();
        media = {
            segments: (mt.match(/#EXTINF/g) || []).length,
            targetDurationSec: (mt.match(/#EXT-X-TARGETDURATION:(\d+)/) || [])[1],
        };
    }
    return {
        isMasterPlaylist: master.includes('#EXT-X-STREAM-INF'),
        isLive: !master.includes('#EXT-X-ENDLIST'),
        variantCount: variants.length,
        firstVariantMedia: media,
    };
}

(async () => {
    let arg = process.argv[2] || '@SkyNews';
    console.log(`\n=== ZeroDelay extraction spike (watch-page method) ===\n`);
    let videoId = arg;
    if (arg.startsWith('@')) {
        videoId = await resolveLive(arg);
        console.log(`Resolved ${arg}/live -> videoId=${videoId}`);
    }
    const ex = await extractHls(videoId);
    console.log(`extract: playability=${ex.status} isLive=${ex.isLive} attempt=${ex.attempt} hls=${!!ex.hlsUrl}`);
    if (!ex.hlsUrl) { console.log('\nRESULT: no HLS after retries.'); process.exit(1); }
    console.log(`hlsUrl (trunc): ${ex.hlsUrl.slice(0, 110)}...\n`);
    const v = await validate(ex.hlsUrl);
    console.log(JSON.stringify(v, null, 2));
    console.log(`\nRESULT: ${v.isMasterPlaylist && v.isLive ? 'extraction WORKS — live master playlist, ExoPlayer-ready ✅' : 'check flags above'}`);
})();
