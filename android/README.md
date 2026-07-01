# ZeroDelay TV

App Android TV / Fire TV que reduz a latência das lives da CazéTV, portando a
lógica de catch-up do ZeroDelay (extensão) pro ExoPlayer/Media3.

## Como funciona

1. Lista os jogos da Copa do Mundo FIFA da CazéTV que estão AO VIVO agora (raspa
   `@CazeTV/streams` e filtra pela tag "COPA DO MUNDO FIFA", excluindo basquete,
   tênis de mesa, programas de estúdio etc.).
2. Ao escolher um jogo, extrai a URL do manifest HLS da página `/watch`
   (a API InnerTube está bloqueada por PoToken; ver `spike/FINDINGS.md`).
3. Toca no ExoPlayer com motor híbrido: `LiveConfiguration` (por modo) faz o
   catch-up suave de velocidade; uma camada portada do ZeroDelay adiciona o
   skip-to-live (> 30s) e os indicadores (latência, buffer, velocidade).

Modos (herdados da extensão): Desligado, Automático (padrão), Suave, Equilibrado,
Próximo, Latência Mínima. No player: OK/seta mostra o overlay; alterna modo e
"Ao vivo"; Voltar esconde o overlay.

## Build

Sem toolchain local, o build oficial é o GitHub Actions
(`.github/workflows/android-tv.yml`): a cada push em `main` que toque `android/**`
ele compila o APK debug e publica na Release `tv-latest`. Rode manualmente pela
aba Actions ("Build ZeroDelay TV APK" > Run workflow) se preferir.

Build local (opcional, precisa Android SDK + JDK 17):

```
cd android
gradle assembleDebug          # ou ./gradlew se houver wrapper
# saída: app/build/outputs/apk/debug/app-debug.apk
```

## Sideload no Fire TV

1. Fire TV: Ajustes > Minha Fire TV > Opções do desenvolvedor > "Apps de fontes
   desconhecidas" ativado para o Downloader.
2. No app Downloader, abra a URL do APK publicado pela Release:
   `https://github.com/<owner>/<repo>/releases/download/tv-latest/zerodelay-tv.apk`
3. Instale e abra "ZeroDelay TV".

## Limitações conhecidas

- Extração depende de scraping da página do YouTube. Vai quebrar quando o YouTube
  mudar o formato; o app tenta re-extrair no erro de playback.
- Validado em lives públicas padrão. Geo-restrição, membership e DRM não testados.
- Extrair e tocar precisam do mesmo IP (URLs do googlevideo são atreladas ao IP),
  o que é natural aqui: tudo roda no mesmo aparelho.
