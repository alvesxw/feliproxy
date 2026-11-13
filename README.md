# FeliProxy

Liga/desliga um proxy SOCKS5 **só para o Discord**, no Windows e no Android.

## Download

Pegue a versão mais recente na página de **Releases** do GitHub:

| Arquivo | O que é |
| --- | --- |
| `FeliProxy-Setup-X.Y.Z.exe` | Instalador para Windows (instala o ProxiFyre junto) |
| `FeliProxy-X.Y.Z.exe` | Versão portátil para Windows (pede admin ao abrir) |
| `FeliProxy-X.Y.Z.apk` | App para Android 8+ |
| `SHA256SUMS.txt` | Hashes para conferir os downloads |

## Windows

Janela que usa o [ProxiFyre](https://github.com/wiresock/proxifyre) por baixo.

- Se o ProxiFyre não estiver instalado, o botão vira **Instalar ProxiFyre**
  (o instalador oficial v2.6.4 vai embutido no .exe, hash conferido no build).
- **Testar proxy** verifica TCP (mostra o IP de saída) e se o proxy suporta UDP (voz).
- **Salvar** grava `C:\Program Files\ProxiFyre\app-config.json` e reinicia o serviço se estiver ligado
  (formato de exemplo em [`app-config.example.json`](app-config.example.json)).

## Android

App que cria uma VPN local valendo **só para o Discord** (`com.discord`) e repassa
o tráfego para o proxy SOCKS5 usando o [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT).

- Ativar/Desativar na tela ou no botão das configurações rápidas (barra de notificações).
- Endereço, usuário, senha e "voz (UDP) também pelo proxy". Desmarcado, um mini-proxy
  local manda o TCP pelo proxy e a voz direto pela conexão normal.
- DNS do Discord é resolvido pelo próprio proxy (mapdns), então não depende de UDP no proxy.

## Servidor

[`server/setup-proxy.sh`](server/setup-proxy.sh) instala um servidor SOCKS5 com UDP (Dante) num Debian/Ubuntu.
Depois, [`server/harden.sh`](server/harden.sh) deixa a VPS segura: SSH só com chave, firewall (ufw),
fail2ban, atualizações de segurança automáticas e proxy aceitando só os logins do grupo `feliproxy`.
Coloque sua chave SSH no servidor **antes** de rodar o `harden.sh` (ele se recusa a rodar sem chave).

```
bash setup-proxy.sh                  # instala (porta 31080) e cria o login "discord"
bash harden.sh                       # endurece a VPS
bash setup-proxy.sh add-user amigo   # um login para cada amigo (mostra a senha)
bash setup-proxy.sh del-user amigo   # tira o acesso de um amigo
bash setup-proxy.sh list-users
```

## Compilar

### Windows (`dist\FeliProxy.exe` e instalador)

Usa o `csc.exe` do .NET Framework que já vem no Windows. O instalador do ProxiFyre é
baixado do GitHub oficial na primeira vez (para `vendor\`) e o hash é conferido.

```
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1   # precisa do Inno Setup 6
```

### Android

JDK 17 e Android SDK 35. As bibliotecas nativas já compiladas estão em `android/app/src/main/jniLibs`.

```
cd android
gradlew assembleRelease
```

Para recompilar a parte nativa (NDK r27c), baixe o submódulo e rode o script:

```
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File android\native\build-native.ps1
```

A chave de assinatura fica em `android\release.jks` + `android\keystore.properties` (fora do git).
Guarde as duas: atualizações do APK precisam ser assinadas com a mesma chave.

## Publicar uma versão

1. Mude o número em [`VERSION`](VERSION) (ex.: `1.0.1`) e faça o commit.
2. Gere os arquivos: `powershell -ExecutionPolicy Bypass -File release.ps1`
   (saem em `release\vX.Y.Z\`; use `-SkipAndroid` para pular o APK).
3. Crie a tag `vX.Y.Z` e suba os arquivos na página de releases do GitHub.

Ou automático: com o workflow [`.github/workflows/release.yml`](.github/workflows/release.yml),
basta `git tag vX.Y.Z` + `git push origin vX.Y.Z` que o GitHub compila e publica a release.
Para o APK sair também, cadastre os secrets descritos no topo do workflow.

## Observações

- O usuário e a senha do proxy ficam em texto puro no `app-config.json` (limitação do ProxiFyre).
- O ProxiFyre é licenciado sob AGPL-3.0; o instalador embutido é o binário oficial, sem modificações.
  Código-fonte: https://github.com/wiresock/proxifyre
- O FeliProxy não é afiliado ao Discord Inc. nem ao projeto ProxiFyre.
