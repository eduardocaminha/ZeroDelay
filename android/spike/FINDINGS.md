# Spike de extração de manifest HLS: achados

Data: 2026-07-01. Objetivo: responder a única pergunta que decide a viabilidade do
app Android (obter a URL do manifest HLS de uma live do YouTube sem API oficial e
sem login).

## CORREÇÃO (2026-07-01, depois do teste em aparelho): abordagem NÃO é viável

O manifest e as media playlists são servidos (HTTP 200), MAS os **segmentos de
vídeo** (`videoplayback` no host `rr1---sn-...googlevideo.com`) retornam **HTTP 403
para qualquer requisição** direta: testado com UA de browser, UA do Media3, sem UA,
com Referer/Origin, IPv4 e IPv6, IP batendo com o `ip=` da URL, `sig` presente e
`expire` no futuro. Confirmado universal em Lofi Girl, CazéTV e NASA (não é canal,
geo nem DRM). `Server: gvs`, body vazio, sem motivo explícito.

Causa: o YouTube (2026) exige um PoToken/SABR na **entrega de mídia**, não só no
InnerTube. Serve o manifest, mas barra o download do vídeo sem o token que só o
player real (com BotGuard) possui. É o mesmo muro que bloqueou o InnerTube, aplicado
uma camada abaixo.

O "Resultado" abaixo (validação só até a media playlist) estava INCOMPLETO: nunca
baixou um segmento `.ts`, que é exatamente onde trava. Fica como lição de
verificação: validar extração de mídia SEMPRE baixando/tocando um segmento real, não
o manifest.

## Resultado (INCOMPLETO: parou na media playlist, ver correção acima)

Aparentava viável pela página `/watch`, não pela API InnerTube. Testado em 4 lives
reais, mas a validação parou na media playlist (não baixou segmento de vídeo).

| Live (canal) | videoId testado | HLS extraído | Master playlist | Segmentos de mídia |
| --- | --- | --- | --- | --- |
| Sky News | YDvsBbKfLPA | sim | 6 variantes | 6 segs, target 5s |
| Al Jazeera | gCNeDWCI0vo | sim | 6 variantes | 6 segs, target 5s |
| Lofi Girl | X4VbdwhkE10 | sim | 6 variantes | 15 segs, target 2s |
| NASA | awQzjn72bI0 | intermitente | ok quando retorna | (ver gotcha 3) |

## O que NÃO funciona (medido, não suposto)

Endpoint InnerTube `POST /youtubei/v1/player`, todos os clientes testados
(TVHTML5, TVHTML5_SIMPLY_EMBEDDED_PLAYER, ANDROID, IOS, MWEB, WEB), sem auth:

- ANDROID: HTTP 400, `FAILED_PRECONDITION` (exige attestation).
- TVHTML5: `LOGIN_REQUIRED`, "Sign in to confirm you're not a bot" (exige PoToken).
- MWEB / WEB: `UNPLAYABLE`, "The page needs to be reloaded" / "Video unavailable".
- TVHTML5_SIMPLY_EMBEDDED_PLAYER: `ERROR`, "no longer supported in this device".
- IOS: `UNPLAYABLE`, "We're processing this video".

Ou seja: a extração ingênua via InnerTube (que o `content.js`/`inject.js` nunca
precisou porque roda dentro da página) está bloqueada por PoToken em 2026.

## O que funciona

`GET https://www.youtube.com/watch?v=<id>` com:

1. User-Agent de browser desktop.
2. Cookie de consentimento `SOCS=CAI` (sem ele a página é um interstitial de
   consentimento, sem player response).

O HTML embute `ytInitialPlayerResponse`, de onde sai `hlsManifestUrl`. Para lives,
essa URL de manifest NÃO precisa de deciframento de assinatura (diferente dos
formatos de VOD adaptativo), por isso toca direto sem PoToken.

## Gotchas (tratados no `extract.mjs`)

1. Cookie de consentimento obrigatório (`SOCS=CAI`).
2. Alguns canais expõem só `adaptiveFormats` + HLS; o app deve preferir HLS pra
   live e ter fallback DASH (`dashManifestUrl`) quando presente. Media3 fala os dois.
3. Resposta não é 100% determinística: às vezes `streamingData` vem ausente e o
   YouTube pede "reload". Resolve-se com retry (o `extract.mjs` tenta até 4x).

## Riscos que isso confirma (para o PRD)

- Fragilidade estrutural: a extração depende de scraping de HTML privado. Vai
  quebrar quando o YouTube mudar o shape do `ytInitialPlayerResponse` ou fechar
  esse caminho (como já fecharam o InnerTube). O motor Android precisa da mesma
  disciplina de resiliência da Fase 0 da extensão (feature-detect, degradar visível).
- ToS: extração fora da API oficial viola os Termos do YouTube. Mesmo território
  do yt-dlp. Aceitável para uso pessoal/sideload, mas é decisão consciente.
- Escopo de vídeo: validado só em lives padrão. Não testado com lives geo-restritas,
  com login, membership, ou DRM. Assumir que uma fração vai falhar.

## Reproduzir

```
cd android/spike
node extract.mjs            # resolve @SkyNews/live e extrai
node extract.mjs <videoId>  # um id específico
node extract.mjs @Canal     # resolve a live atual de um canal
```

## Próximo passo

Portar essa chamada HTTP (2 requests: página + manifest) pra Kotlin/OkHttp dentro
do app, e alimentar a URL num `ExoPlayer` com `LiveConfiguration`. A partir daí, a
camada de modos/catch-up híbrida (ver decisão no PRD).
