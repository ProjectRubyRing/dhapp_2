# secure-api への HTTPS 接続確認 API（JVM / JBoss EAP の各トラストストア）

`GET /api/secure-api/call` は、**JVM が管理するトラストストア**と
**JBoss EAP(Elytron) が管理するトラストストア**のそれぞれで、compose の `secure-api`
サービスへ HTTPS 接続し、TLS ハンドシェイクの内容と HTTP 応答を詳細に返す。
同じ接続先・同じアプリコードで**トラストストアの違いだけ**を比較できる。

| trustSource | トラストストアの実体 | SSLContext の作り方 |
|---|---|---|
| `JVM` | `-Djavax.net.ssl.trustStore` が指すストア（未指定なら `$JAVA_HOME/lib/security/cacerts`） | `SSLContext.getDefault()`（アプリ側は何も設定しない） |
| `JBOSS_EAP` | elytron の `key-store`（例 `appTrustStore` → `$JBOSS_HOME/standalone/configuration/jboss-truststore.p12`） | そのファイルから `TrustManagerFactory` を組み立てた専用 SSLContext |
| `NONE` | 空のトラストストア（対照実験） | 信頼できる CA が 0 枚 → **失敗するのが正しい** |

**検証を緩めるオプションは用意していない。** ホスト名検証（endpoint identification）も
有効なまま。「取り込んだ証明書で検証できたから通信できた」ことを確認する API のため。

## 接続先（別リポジトリ `Container_Compose_file` の compose 定義に対応）

| target | URL（既定） | 経路 |
|---|---|---|
| `direct`（既定） | `https://secure-api:8443/api/v1/ping` | secure-api へ直接。WireMock を `--disable-http` で起動しているため **HTTPS 必須** |
| `alb` | `https://alb/secure/v1/ping` | ALB で TLS 終端 → secure-api へ再暗号化 |

secure-api のサーバ証明書は `pki-init`（`compose/pki/gen-certs.sh`）が発行し、
その発行元 CA が受領した自己証明書 `cacert.crt` にあたる。front/back コンテナの
`entrypoint.sh` が `cacert.crt` を **JDK 側ストアと JBoss 側ストアの両方**へ取り込む構成なので、
本 API では「両方とも取り込みが効いているか」を 1 回の呼び出しで確認できる。

## エンドポイント

| メソッド | パス | 内容 |
|---|---|---|
| `GET` | `/api/secure-api/call` | 2 系統のトラストストアで接続し、詳細を JSON で返す |
| `POST` | `/api/secure-api/call` | 同上（POST 版） |
| `GET` | `/api/secure-api/call?format=text` | ログ・コンソールと同じテキストレポートを返す |
| `GET` | `/api/secure-api/truststores` | 接続せず、両ストアの中身と elytron の登録状態を返す |

接続に失敗しても HTTP 500 にはせず **200 + `status`** で返す（どちらのストアで失敗したかを
読み取るための API のため）。

### クエリパラメータ

| パラメータ | 既定 | 内容 |
|---|---|---|
| `target` | `direct` | `direct` / `alb` |
| `url` | （`target` の既定 URL） | 任意の接続先。指定すると `target=custom` |
| `trust` | `jvm,jboss` | `jvm` / `jboss` / `none` / `all`（対照実験込み）／カンマ区切り |
| `method` | `GET` | HTTP メソッド |
| `body` / `contentType` | — | GET 以外のときに送るボディ |
| `format` | — | `text` でテキストレポート |

## 動作確認

```
# JVM 側・JBoss EAP 側の両方で secure-api へ接続（本命）
curl -s http://localhost:8080/iwinmichl/api/secure-api/call | jq .

# 画面表示用のテキストレポート（ログ・コンソール出力と同じ内容）
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?format=text"

# 要点だけ
curl -s http://localhost:8080/iwinmichl/api/secure-api/call \
  | jq '{status, jvm: .comparison.jvmStatus, jboss: .comparison.jbossStatus,
         both: .comparison.bothSucceeded, summary: .comparison.summary}'

# ALB 経由 / 対照実験（空ストア）込み / 片方だけ / 任意 URL
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?target=alb"
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?trust=all"
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?trust=jboss"
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?url=https://secure-api:8443/api/v1/health"

# 接続せずトラストストアの中身だけ見る（切り分け用）
curl -s "http://localhost:8080/iwinmichl/api/secure-api/truststores?format=text"
```

## レスポンス

```jsonc
{
  "requestId": "…",
  "timestamp": "2026-09-01T12:34:56.789+09:00",
  "target": "direct",
  "url": "https://secure-api:8443/api/v1/ping",
  "method": "GET",
  "status": "SUCCESS",                     // SUCCESS / PARTIAL / FAILED（NONE は判定から除外）
  "results": [
    {
      "trustSource": "JVM",
      "label": "JVM が管理するトラストストア（-Djavax.net.ssl.trustStore）",
      "status": "SUCCESS",                 // SUCCESS / TLS_HANDSHAKE_FAILED / CONNECT_FAILED /
                                           // TRUSTSTORE_UNAVAILABLE / INVALID_URL / ERROR
      "trustStoreResolution": "システムプロパティ javax.net.ssl.trustStore（…）",
      "trustStore": { "path": "/opt/jboss/certs/truststore.jks", "type": "JKS",
                      "entryCount": 152, "aliases": ["cacert", …] },
      "sslContextOrigin": "SSLContext.getDefault()（JVM 既定）",
      "tlsProtocol": "TLSv1.3",
      "cipherSuite": "TLS_AES_256_GCM_SHA384",
      "peerPrincipal": "CN=secure-api",
      "handshakeElapsedMs": 34,
      "serverCertificates": [
        { "position": 0, "subjectDn": "CN=secure-api", "issuerDn": "CN=local-test-ca",
          "sha256Fingerprint": "…", "inTrustStore": false,
          "subjectAlternativeNames": ["DNS:secure-api", "DNS:localhost", "IP:127.0.0.1"] },
        { "position": 1, "subjectDn": "CN=local-test-ca", "inTrustStore": true,
          "trustStoreAlias": "cacert" }
      ],
      "verifiedByTrustStore": true,
      "trustAnchorAlias": "cacert",        // ★どの証明書で検証できたか
      "httpStatus": 200,
      "responseBodyPreview": "{\"status\":\"ok\"}",
      "message": "…でサーバ証明書を検証し、HTTPS 通信に成功した。…",
      "elapsedMs": 88
    },
    { "trustSource": "JBOSS_EAP",
      "trustStoreResolution": "elytron の key-store=appTrustStore（path=jboss-truststore.p12, relative-to=jboss.server.config.dir）",
      "elytronKeyStoreName": "appTrustStore",
      "elytronKeyStoreAttributes": { "path": "jboss-truststore.p12", "type": "PKCS12", … },
      "trustStore": { "path": "/opt/server/standalone/configuration/jboss-truststore.p12",
                      "type": "PKCS12", "entryCount": 2, "aliases": ["cacert", …] },
      "sslContextOrigin": "elytron の key-store が指すファイルから組み立てた…",
      "status": "SUCCESS", "trustAnchorAlias": "cacert", "httpStatus": 200, … }
  ],
  "comparison": {
    "jvmStatus": "SUCCESS", "jbossStatus": "SUCCESS",
    "jvmTrustStorePath": "/opt/jboss/certs/truststore.jks",
    "jbossTrustStorePath": "/opt/server/standalone/configuration/jboss-truststore.p12",
    "jvmTrustAnchorAlias": "cacert", "jbossTrustAnchorAlias": "cacert",
    "bothSucceeded": true, "consistent": true, "sameServerCertificate": true,
    "summary": "★JVM 側・JBoss EAP 側のどちらのトラストストアでも secure-api への HTTPS 接続に成功した。…"
  },
  "report": "…",                           // ログ・コンソールへ出したテキストと同一
  "elapsedMs": 190
}
```

失敗時は `exceptionClass` と `causeChain`（原因の全段。PKIX の失敗理由は最下段に出る）、
および `hint`（どちらのストアへ何を取り込むか）が入る。

## JBoss EAP 側トラストストアの解決順

アプリはパスを決め打ちせず、次の順に解決する。jboss-cli 側の定義を変えても追随する。

1. `app.secure-api.jboss.truststore-path`（環境変数 `JBOSS_TRUSTSTORE_FILE`）
2. **elytron の `key-store`** の `path` / `relative-to` を管理モデル（JMX ファサード
   `jboss.as:subsystem=elytron,key-store=*`）から読む。候補は
   `app.secure-api.jboss.elytron-key-store`（既定 `appTrustStore`）→
   `app.tls.elytron.key-store`（既定 `cacertTrustStore`）→ 定義されている全 `key-store` の順で、
   **実ファイルが存在するもの**を優先する
3. `${jboss.server.config.dir}/jboss-truststore.p12`

解決結果は `trustStoreResolution` にそのまま入るので、どれが使われたかはレスポンスで分かる。

## 判定の読み方

| 状況 | 意味 / 対処 |
|---|---|
| `bothSucceeded: true` | 2 系統とも証明書の取り込みが効いている（期待どおり） |
| JVM だけ成功 | elytron の `key-store` が指すファイルに発行元証明書が入っていない。`/api/secure-api/truststores` で中身を確認し、`keytool -importcert -alias cacert -file cacert.crt -keystore <…>/jboss-truststore.p12 -storetype PKCS12` で取り込む |
| JBoss EAP だけ成功 | `-Djavax.net.ssl.trustStore` が指すストアへの取り込みを確認する |
| 両方 `TLS_HANDSHAKE_FAILED` | どちらのストアにも発行元が無い。`cacert.crt` の取り込み自体を確認する |
| 両方 `CONNECT_FAILED` | 証明書ではなく到達性の問題。`docker compose ps secure-api` を確認する（secure-api は `--disable-http` のため平文 HTTP では待ち受けていない） |
| `NONE` が `SUCCESS` | ★どこかで証明書検証が迂回されている疑い（対照実験は失敗するのが正しい） |
| `sameServerCertificate: false` | 2 経路で違うサーバ証明書を見ている。接続先を確認する |

## ログ・コンソール出力

`GET /api/config/date-config` と同様、結果は**ログ**（`LOG_OUT_DIR` 配下の各ログファイル）と
**コンソール**（標準出力・UTF-8 固定）へ同じテキストレポートで出力され、
レスポンスの `report`／`?format=text` からも同じものが取得できる。

```
================================================================================
secure-api への HTTPS 接続確認（JVM / JBoss EAP の各トラストストア）
requestId=…, timestamp=…, status=SUCCESS, elapsedMs=190
target=direct, url=https://secure-api:8443/api/v1/ping, method=GET
================================================================================

[1] JVM が管理するトラストストア（-Djavax.net.ssl.trustStore）
  status          : SUCCESS
  trustStorePath  : /opt/jboss/certs/truststore.jks
  tlsProtocol     : TLSv1.3
  verifiedByStore : true（trustAnchorAlias=cacert）
  --- サーバ証明書チェーン ---
      [0] subject=CN=secure-api
          issuer   = CN=local-test-ca
          …
  httpStatus      : 200

[2] JBoss EAP が管理するトラストストア（elytron key-store）
  …

[比較] JVM 管理ストア vs JBoss EAP 管理ストア
  bothSucceeded   : true
  summary         : ★JVM 側・JBoss EAP 側のどちらのトラストストアでも…
================================================================================
```

## 設定

`src/main/resources/application.yml` の `app.secure-api` 配下。すべて環境変数で上書きできる。

| 設定 | 環境変数 | 既定値 |
|---|---|---|
| `url` | `SECURE_API_URL` | `https://secure-api:8443/api/v1/ping` |
| `via-alb-url` | `SECURE_API_VIA_ALB_URL` | `https://alb/secure/v1/ping` |
| `connect-timeout-ms` | `SECURE_API_CONNECT_TIMEOUT_MS` | `3000` |
| `read-timeout-ms` | `SECURE_API_READ_TIMEOUT_MS` | `5000` |
| `jboss.truststore-path` | `JBOSS_TRUSTSTORE_FILE` | （空 → elytron から解決） |
| `jboss.truststore-password` | `JBOSS_TRUSTSTORE_PASSWORD` | `changeit` |
| `jboss.truststore-type` | `JBOSS_TRUSTSTORE_TYPE` | （空 → 中身から自動判別） |
| `jboss.elytron-key-store` | `SECURE_API_ELYTRON_KEY_STORE` | `appTrustStore` |
| `jboss.truststore-file-name` | `JBOSS_TRUSTSTORE_FILE_NAME` | `jboss-truststore.p12` |

`SECURE_API_URL` / `SECURE_API_VIA_ALB_URL` / `JBOSS_TRUSTSTORE_PASSWORD` は
compose 側（front/back コンテナ）で既に定義されている環境変数名に合わせてある。

## 既存 TLS API との違い

| API | 使うトラストストア | 用途 |
|---|---|---|
| `POST /api/tls/call` | JVM 既定の SSLContext のみ | 任意 URL への HTTPS 通信が通るかの確認 |
| `GET /api/tls/config` | JVM 既定 + elytron の**登録状態**の確認 | 設定が正しく入っているかの点検 |
| `GET /api/secure-api/call` | **JVM 管理ストアと JBoss EAP 管理ストアの両方で実接続** | 2 系統の取り込みを同一条件で比較する |

## 実装

| クラス | 役割 |
|---|---|
| `controller/SecureApiController` | エンドポイント。`format=text` の切り替え |
| `service/SecureApiTlsService` | トラストストアの解決・SSLContext の組み立て・接続・比較・レポート |
| `service/TrustStoreInspector` | トラストストアの読み取り（`loadFrom` で任意パスにも対応） |
| `service/ElytronSslInspector` | elytron の管理モデル読み取り（`key-store` の `path` / `relative-to`） |
| `service/TlsHttpsClient` | 生 `SSLSocket` でのハンドシェイク・証明書チェーンの記述（再利用） |

`src/test/java/com/example/dhapp/service/SecureApiTlsServiceTest` で、トラストストアの解決・
パラメータ解釈・接続失敗時の詰め方を確認している（secure-api への実接続は compose 環境が必要）。
