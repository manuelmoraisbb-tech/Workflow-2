# MB Animes — app Android

App nativo (Kotlin) que envolve o site `mb-animes.ct.ws` e acrescenta o que um site não consegue:

- **Gestor de downloads nativo**: fila, pausa/retoma, progresso em notificação, só Wi-Fi, apagar. Suporta mp4 direto (retoma por Range) e HLS/m3u8 (junta os segmentos; também HLS com AES-128).
- **Player offline** (Media3/ExoPlayer) que lembra a posição.
- **Favoritos** e **Continuar a assistir** (separador *Biblioteca*, guardado no aparelho; também funciona no site).
- Barra inferior (Início · Navegar · Biblioteca · Downloads), splash, ecrã inteiro com rotação automática, ecrã de erro offline, links externos abrem no navegador.
- Preparado para a Play Store: `targetSdk 36`, R8 + shrink de recursos, `versionCode` automático, workflow de release que gera **AAB + APK assinados**.

## Como o download funciona
O botão "Baixar" do player chama `window.MBApp.download(...)` (só existe dentro do app). O app descarrega o mesmo link que está a tocar, com os cookies do WebView (o InfinityFree bloqueia pedidos sem o cookie anti-bot). Os ficheiros ficam na pasta privada do app (sem pedir permissão de armazenamento; apagam-se ao desinstalar).

## Compilar
- **Debug**: faz push para `main` → Actions > *Build APK (debug)* → artefacto `app-debug`.
- **Release**: ver abaixo.

## Assinar e gerar o release
1. Cria a chave (uma vez; **guarda o ficheiro e as senhas em vários sítios — se perderes, não consegues atualizar o app**):
   ```
   keytool -genkeypair -v -keystore mbanimes.jks -alias mbanimes -keyalg RSA -keysize 2048 -validity 10000
   ```
   (Termux: `pkg install openjdk-17`)
2. No GitHub: Settings > Secrets and variables > Actions > New secret:
   - `KEYSTORE_BASE64` = saída de `base64 -w0 mbanimes.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (ex.: `mbanimes`), `KEY_PASSWORD`
3. Actions > *Release (AAB + APK assinados)* > Run workflow. Sai `app-release.aab` (Play Store) e `app-release.apk` (para o teu site). O `versionCode` sobe sozinho a cada execução.

## Antes de publicar — checklist
- [ ] Sobe os ficheiros PHP novos/alterados para o site (zip `mb-animes-php`) e adiciona em `app/config.php`: `'contact_email' => 'o-teu@email.com'` (usado nas páginas `/privacidade` e `/termos`).
- [ ] Mudaste de domínio? Altera `SITE_URL` em `app/build.gradle.kts` **e** `android:host` no `AndroidManifest.xml`.
- [ ] `applicationId` (`com.mbanimes.app`) é definitivo depois da primeira publicação.
- [ ] Rever a Política de Privacidade (`/privacidade`) — é um modelo, não aconselhamento jurídico.
- [ ] Play Console: ícone 512×512, imagem de destaque 1024×500, ≥2 capturas de ecrã, classificação etária, formulário *Data safety*, URL da política de privacidade.
- [ ] Testar num telemóvel real: download mp4, download HLS, pausa/retoma, ver offline, rodar o ecrã, modo avião.

## Aviso importante sobre a Google Play
O Google Play exige que tenhas direitos sobre o conteúdo que o app mostra. Se os episódios vêm de servidores de terceiros sem licença, a revisão pode rejeitar o app ou suspender a conta de programador. Alternativas: distribuir o APK pelo teu site/Facebook, ou publicar na Play Store só com conteúdo licenciado/próprio.

## Estrutura
```
app/src/main/java/com/mbanimes/app/
  MainActivity.kt      WebView, abas, painel de downloads, ecrã inteiro
  WebBridge.kt         ponte window.MBApp ↔ downloads
  PlayerActivity.kt    player offline
  download/            Engine (mp4/HLS), fila, serviço, lista persistente, adapter
```

## Camada de API (novo)
- `Api.kt`: cliente de `/api/v1` (login, tokens, chamadas JSON). `Session` guarda o token.
- `ChallengeInterceptor.kt`: resolve o desafio JavaScript do InfinityFree (cookie `__test`) dentro do OkHttp. **É a parte frágil**: se o InfinityFree mudar o desafio, a API deixa de responder ao app.
- Na versão debug, ao abrir o app aparece um aviso "API OK" ou o erro — usa-o para confirmar que tudo liga.
