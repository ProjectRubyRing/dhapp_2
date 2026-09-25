# 自己署名証明書 (cacert.crt) による HTTPS 通信確認 API

JVM のトラストストアに登録した**自己署名証明書 `cacert.crt`** を使って、

1. **REST API から HTTPS 通信できること**（`POST /api/tls/call`）
2. **JBoss CLI で行った登録（トラストマネージャー／クライアント SSL コンテキスト／JVM 既定 SSL
   コンテキスト）が正しく反映されていること**（`GET /api/tls/config`）
3. **Java アプリではなく `curl` からも同じ証明書で接続できること**（`scripts/verify-truststore-curl.sh`）

を確認するための機能一式。1・2 は本アプリの REST API、3 はシェルスクリプトで確認する。

- 実装: `com.example.dhapp.controller.TlsController`
- 通信: `com.example.dhapp.service.TlsHttpsClient`
- 設定確認: `com.example.dhapp.service.TlsConfigCheckService` /
  `TrustStoreInspector` / `ElytronSslInspector`
- JBoss CLI: `wildfly/configure-truststore.cli`
- curl 検証: `scripts/verify-truststore-curl.sh`

---

## 1. 前提とする環境

自己署名証明書は **JVM のトラストストアに import 済み**で、**JBoss EAP の standalone 起動時に
パラメータでトラストストアとパスワードが渡されている**ことを前提とする。
アプリはトラストストアのパスやパスワードを自前では持たない。

### 1.1 トラストストアへの import

```bash
keytool -importcert -trustcacerts -noprompt \
    -alias    cacert \
    -file     /opt/jboss/certs/cacert.crt \
    -keystore /opt/jboss/certs/truststore.jks \
    -storepass "$TRUSTSTORE_PASSWORD"

# 確認（SHA-256 フィンガープリントが cacert.crt と一致すること）
keytool -list -v -alias cacert -keystore /opt/jboss/certs/truststore.jks \
    -storepass "$TRUSTSTORE_PASSWORD" | grep -A1 'SHA256'
openssl x509 -in /opt/jboss/certs/cacert.crt -noout -fingerprint -sha256
```

### 1.2 standalone 起動パラメータ

`$JBOSS_HOME/bin/standalone.conf` の `JAVA_OPTS`（またはコンテナの起動コマンド）に追加する。

```bash
JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStore=/opt/jboss/certs/truststore.jks"
JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStorePassword=<password>"
JAVA_OPTS="$JAVA_OPTS -Djavax.net.ssl.trustStoreType=JKS"
```

この 3 つが実際に渡っているかは `GET /api/tls/config` の
`jvm.truststore.property` / `jvm.truststore.password-property` で確認できる
（**パスワードの値はレスポンスにもログにも出力しない**。指定有無だけを返す）。

### 1.3 JBoss CLI での elytron 登録

`wildfly/configure-truststore.cli` を実行する（内容は [6 章](#6-jboss-cli-による登録と確認)）。

```bash
$JBOSS_HOME/bin/jboss-cli.sh --file=wildfly/configure-truststore.cli
```

---

## 2. エンドポイント

| メソッド | パス | 内容 |
|---|---|---|
| `POST` | `/api/tls/call` | 指定 URL へ、トラストストアの cacert.crt で検証しながら HTTPS 通信する |
| `GET` | `/api/tls/call?url=...` | 上記の簡易版（curl 1 行で叩ける GET 版） |
| `GET` | `/api/tls/config` | トラストストア／elytron の設定が正しいかを確認する |

コンテキストパスは `/iwinmichl` なので、実際の URL は次のようになる。

```
http://localhost:8080/iwinmichl/api/tls/call
http://localhost:8080/iwinmichl/api/tls/config
```

> 通信失敗・設定不備でも **HTTP 500 にはせず 200 + `status`** で返す。
> 検証 API として結果を機械的に読み取れるようにするため（既存の `/api/external/execute` と同方針）。

---

## 3. `POST /api/tls/call` — 自己署名証明書での HTTPS 通信

### 3.1 何を使って通信するか

本 API は**独自のトラストマネージャを組み立てず、JVM 既定の SSLContext
（`SSLContext.getDefault()`）だけ**を使う。そのため次のどちらの経路で登録された証明書でも
そのまま検証に使われ、「設定が効いているか」がそのまま通信結果に現れる。

| 経路 | 効くもの |
|---|---|
| standalone 起動パラメータ `-Djavax.net.ssl.trustStore` | elytron の `default-ssl-context` が**未設定**のとき |
| `/subsystem=elytron:write-attribute(name=default-ssl-context, ...)` | 設定されているとき（Elytron が起動時に `SSLContext.setDefault()` を実行するため**こちらが優先**） |

> **検証を緩めるオプションは用意していない。** ホスト名検証（endpoint identification = HTTPS）も
> 有効なまま。「証明書を信頼できたから通信できた」ことを確認する API なので、
> 検証を無効化できると意味を失うため。

処理は 2 段階で行う。

1. 生の `SSLSocket` でハンドシェイク → TLS プロトコル・暗号スイート・サーバ証明書チェーンを取得
   （`HttpsURLConnection` からはネゴシエートされた TLS バージョンが取れないため）
2. `HttpsURLConnection` で実際にリクエスト → HTTP ステータスとボディを取得

1 が成功して 2 が失敗した場合でも、1 の結果はレスポンスに残るので切り分けができる。

### 3.2 リクエスト

`Content-Type: application/json`。**全項目が任意**。

| 項目 | 型 | 既定 | 内容 |
|---|---|---|---|
| `url` | string | `app.tls.target-url` | 接続先。**`https://` のみ**（http はバリデーションエラー 400） |
| `method` | string | `GET` | `GET` / `POST` / `PUT` / `DELETE` / `HEAD` / `OPTIONS` / `PATCH` |
| `body` | string | なし | `GET` / `HEAD` 以外のときに送るリクエストボディ（最大 1MB） |
| `contentType` | string | `application/json` | `body` の Content-Type |

### 3.3 レスポンス項目

| 項目 | 内容 |
|---|---|
| `status` | `SUCCESS` / `TLS_HANDSHAKE_FAILED` / `CONNECT_FAILED` / `INVALID_URL` / `ERROR` |
| `requestId` | ログとの突き合わせ用 UUID |
| `url` / `host` / `port` / `method` | 実際に使った接続先 |
| `httpStatus` | HTTP ステータス。ハンドシェイク失敗時は `null` |
| `responseContentType` / `responseBodyLength` | 応答のヘッダ由来の情報 |
| `responseBodyPreview` | 応答ボディの先頭 2048 文字（超過時は `...(truncated)`） |
| `tlsProtocol` | ネゴシエートされた TLS バージョン（`TLSv1.3` など） |
| `cipherSuite` | ネゴシエートされた暗号スイート |
| `peerPrincipal` | サーバ証明書の Subject |
| `handshakeElapsedMs` | ハンドシェイク所要時間 |
| **`verifiedByTrustStore`** | **チェーンのトラストアンカーが JVM トラストストア内で見つかったか**。自己署名証明書での検証が効いていれば `true` |
| **`trustAnchorAlias`** | 一致したトラストストアのエイリアス（例: `cacert`） |
| `serverCertificates[]` | サーバ証明書チェーン（下表） |
| `trustStore` | 使用した JVM トラストストアの状態（パス・種別・エントリ数など） |
| `sslContextProtocol` / `sslContextProvider` | `SSLContext.getDefault()` の素性。Elytron 由来だとプロバイダ名が WildFly 系になる |
| `elapsedMs` | API 全体の処理時間 |
| `message` / `hint` | 結果の説明と、失敗時の対処 |
| `exceptionClass` | 失敗時の例外クラス名 |

`serverCertificates[]` の各要素:

| 項目 | 内容 |
|---|---|
| `position` | チェーン内の位置（0 = サーバ証明書） |
| `subjectDn` / `issuerDn` | Subject / Issuer |
| `serialNumber` / `signatureAlgorithm` | シリアル（16 進）／署名アルゴリズム |
| `notBefore` / `notAfter` / `expired` | 有効期間と期限切れ判定 |
| `selfSigned` | Subject と Issuer が一致（＝自己署名） |
| `sha256Fingerprint` | `keytool -list -v` / `openssl x509 -fingerprint -sha256` と同じ形式 |
| `inTrustStore` / `trustStoreAlias` | この証明書自体がトラストストアに登録されているか |
| `subjectAlternativeNames` | `DNS:` / `IP:` 形式の SAN（ホスト名検証の確認用） |

> 証明書の同一性は **SHA-256 フィンガープリント**で判定する。DN は重複し得るし、
> エイリアスは import 時に任意に付けられるため、「cacert.crt そのものが登録されているか」を
> 確実に判定できるのはフィンガープリントだけ。

### 3.4 実行例（成功）

```bash
curl -i -X POST http://localhost:8080/iwinmichl/api/tls/call \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.internal:8443/health"}'
```

```json
{
  "status": "SUCCESS",
  "requestId": "6f0f1f0e-...",
  "url": "https://example.internal:8443/health",
  "host": "example.internal",
  "port": 8443,
  "method": "GET",
  "httpStatus": 200,
  "responseContentType": "application/json",
  "responseBodyPreview": "{\"status\":\"UP\"}",
  "tlsProtocol": "TLSv1.3",
  "cipherSuite": "TLS_AES_256_GCM_SHA384",
  "peerPrincipal": "CN=example.internal,OU=dev,O=example,C=JP",
  "handshakeElapsedMs": 42,
  "verifiedByTrustStore": true,
  "trustAnchorAlias": "cacert",
  "serverCertificates": [
    {
      "position": 0,
      "subjectDn": "CN=example.internal,OU=dev,O=example,C=JP",
      "issuerDn": "CN=example.internal,OU=dev,O=example,C=JP",
      "selfSigned": true,
      "expired": false,
      "sha256Fingerprint": "3B:1F:...:9C",
      "inTrustStore": true,
      "trustStoreAlias": "cacert",
      "subjectAlternativeNames": ["DNS:example.internal"]
    }
  ],
  "trustStore": {
    "source": "javax.net.ssl.trustStore（JBoss EAP standalone 起動パラメータ）",
    "path": "/opt/jboss/certs/truststore.jks",
    "type": "JKS",
    "pathPropertyProvided": true,
    "passwordPropertyProvided": true,
    "loaded": true,
    "entryCount": 1,
    "certificateEntryCount": 1,
    "aliases": ["cacert"]
  },
  "sslContextProvider": "SunJSSE",
  "elapsedMs": 96,
  "message": "JVM 既定の SSLContext（トラストストア: /opt/jboss/certs/truststore.jks）でサーバ証明書を検証し、HTTPS 通信に成功した。"
}
```

**`verifiedByTrustStore: true` かつ `trustAnchorAlias` がトラストストアのエイリアスであること**が、
「トラストストアの cacert.crt を使って HTTPS 通信できた」ことの根拠になる。

### 3.5 実行例（トラストストア未登録）

```json
{
  "status": "TLS_HANDSHAKE_FAILED",
  "tlsProtocol": null,
  "exceptionClass": "javax.net.ssl.SSLHandshakeException",
  "message": "TLS ハンドシェイクに失敗した: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target",
  "hint": "サーバ証明書をトラストストアで検証できていない。(1) GET /api/tls/config で ... (2) 未登録なら keytool -importcert ..."
}
```

### 3.6 GET 版

```bash
curl -s "http://localhost:8080/iwinmichl/api/tls/call?url=https://example.internal:8443/health" | jq .

# url 省略時は app.tls.target-url（環境変数 TLS_TARGET_URL）へ接続する
curl -s http://localhost:8080/iwinmichl/api/tls/call | jq '.status, .verifiedByTrustStore'
```

---

## 4. `GET /api/tls/config` — 登録内容の確認

JBoss CLI で行った登録が **実際に通信を行う JVM の内側から見て**反映されているかを確認する。
elytron の管理モデルは、管理モデルの JMX ファサード（ドメイン `jboss.as`）経由で読む
（EAP 既定の standalone.xml には jmx サブシステムが `expose-resolved-model` 付きで入っているため、
追加依存なしで jboss-cli と同じ値が読める）。

### 4.1 クエリパラメータ

| 名前 | 既定 | 内容 |
|---|---|---|
| `probe` | `false` | `true` にすると `app.tls.target-url` へ実際に TLS ハンドシェイクし、設定が実通信に効いているかまで確認する |

### 4.2 チェック項目

| `name` | 分類 | 何を確認するか |
|---|---|---|
| `jvm.truststore.property` | jvm-truststore | `-Djavax.net.ssl.trustStore` が起動パラメータで指定されている |
| `jvm.truststore.password-property` | jvm-truststore | `-Djavax.net.ssl.trustStorePassword` が指定されている（値は非表示） |
| `jvm.truststore.file` | jvm-truststore | そのファイルが存在し KeyStore としてロードできる |
| `jvm.truststore.contains-cacert` | jvm-truststore | **cacert.crt がトラストストアに登録されている**（SHA-256 一致） |
| `jvm.default-trust-manager` | jvm-truststore | 既定の `TrustManagerFactory`（PKIX）が cacert.crt を信頼済みイシュアとして採用する |
| `elytron.key-store` | elytron | **key-store が登録されている**／`javax.net.ssl.trustStore` と同じファイルを指しているか |
| `elytron.trust-manager` | elytron | **トラストマネージャーへの登録**と、その `key-store` 参照が正しい |
| `elytron.client-ssl-context` | elytron | **クライアント SSL コンテキストへの登録**と、その `trust-manager` 参照が正しい |
| `elytron.default-ssl-context` | elytron | **JVM 既定 SSL コンテキストへの登録**（`/subsystem=elytron` の `default-ssl-context`） |
| `jvm.default-ssl-context` | jvm-ssl-context | 実行中の `SSLContext.getDefault()` が上記の設定を反映しているか |
| `tls.handshake-probe` | tls-handshake | （`probe=true` のみ）実際にハンドシェイクして最終確認する |

各チェックは `status` が `OK` / `NG` / `UNKNOWN` のいずれか。
`UNKNOWN` は「設定が誤っている」ではなく「判定材料が取れなかった」ことを示す
（jmx サブシステムが無効、`cacert.crt` のパスが未設定など）。
全体の `status` は **1 つでも NG があれば `NG`、NG が無く UNKNOWN があれば `WARN`、すべて OK なら `OK`**。

### 4.3 実行例

```bash
# 設定だけ確認
curl -s http://localhost:8080/iwinmichl/api/tls/config | jq .

# 実通信まで含めて確認
curl -s "http://localhost:8080/iwinmichl/api/tls/config?probe=true" | jq .

# 結果の要約だけ見る
curl -s http://localhost:8080/iwinmichl/api/tls/config \
  | jq '{status, okCount, ngCount, unknownCount}'

# NG のものだけ、対処方法つきで抜き出す
curl -s http://localhost:8080/iwinmichl/api/tls/config \
  | jq '.checks[] | select(.status != "OK") | {name, status, expected, actual, hint}'
```

```json
{
  "status": "OK",
  "okCount": 10, "ngCount": 0, "unknownCount": 0,
  "trustStore": { "path": "/opt/jboss/certs/truststore.jks", "loaded": true, "aliases": ["cacert"] },
  "caCertPath": "/opt/jboss/certs/cacert.crt",
  "caCertificate": { "selfSigned": true, "inTrustStore": true, "trustStoreAlias": "cacert" },
  "elytron": {
    "available": true,
    "source": "JMX (jboss.as:subsystem=elytron)",
    "defaultSslContext": "cacertClientSslContext",
    "trustManagerNames": ["cacertTrustManager"],
    "clientSslContextNames": ["cacertClientSslContext"]
  },
  "checks": [
    {
      "name": "elytron.client-ssl-context",
      "category": "elytron",
      "status": "OK",
      "expected": "client-ssl-context=cacertClientSslContext が trust-manager=cacertTrustManager を参照している",
      "actual": "登録あり（trust-manager=cacertTrustManager, protocols=[TLSv1.3, TLSv1.2]）"
    }
  ],
  "message": "トラストストア・トラストマネージャー・クライアント SSL コンテキスト・JVM 既定 SSL コンテキストのすべてが期待どおり設定されている。"
}
```

NG のときは `checks[].hint` にそのまま実行できる `keytool` / `jboss-cli` のコマンドが入る。

---

## 5. 設定（環境変数）

| 環境変数 | 既定値 | 内容 |
|---|---|---|
| `TLS_TARGET_URL` | `https://localhost:8443/` | `url` 省略時の接続先。`probe=true` の接続先でもある |
| `TLS_CONNECT_TIMEOUT_MS` | `3000` | 接続タイムアウト |
| `TLS_READ_TIMEOUT_MS` | `5000` | 読み取りタイムアウト |
| `TLS_CA_CERT_PATH` | （空） | 照合に使う `cacert.crt` のパス。設定するとフィンガープリント照合ができる |
| `TLS_EXPECTED_ALIAS` | `cacert` | `TLS_CA_CERT_PATH` が読めないときに存在確認するエイリアス |
| `TLS_ELYTRON_KEY_STORE` | `cacertTrustStore` | 期待する elytron の key-store 名 |
| `TLS_ELYTRON_TRUST_MANAGER` | `cacertTrustManager` | 期待する trust-manager 名 |
| `TLS_ELYTRON_CLIENT_SSL_CONTEXT` | `cacertClientSslContext` | 期待する client-ssl-context 名 |

```bash
export TLS_TARGET_URL=https://example.internal:8443/health
export TLS_CA_CERT_PATH=/opt/jboss/certs/cacert.crt
```

`TLS_CA_CERT_PATH` を設定しないと `jvm.truststore.contains-cacert` はエイリアス名での判定に
なる（`UNKNOWN` になることがある）。**確実な確認のために設定を推奨**。

---

## 6. JBoss CLI による登録と確認

### 6.1 登録

`wildfly/configure-truststore.cli` が次の 4 つを行う。

```
1. /subsystem=elytron/key-store=cacertTrustStore
       path=<トラストストア>, type=JKS, credential-reference={clear-text=<password>}
2. /subsystem=elytron/trust-manager=cacertTrustManager
       key-store=cacertTrustStore, algorithm=PKIX
3. /subsystem=elytron/client-ssl-context=cacertClientSslContext
       trust-manager=cacertTrustManager, protocols=["TLSv1.3","TLSv1.2"]
4. /subsystem=elytron:write-attribute(name=default-ssl-context, value=cacertClientSslContext)
```

```bash
# 未起動のサーバ構成へ適用（embed-server を使うので停止中に実行する）
TRUSTSTORE_PATH=/opt/jboss/certs/truststore.jks \
TRUSTSTORE_TYPE=JKS \
TRUSTSTORE_PASSWORD=<password> \
  $JBOSS_HOME/bin/jboss-cli.sh --file=wildfly/configure-truststore.cli
```

起動中のサーバへ適用する場合は、ファイル先頭の `embed-server` と末尾の `stop-embedded-server` を
外して `--connect` で実行し、最後に `:reload` する。
**`default-ssl-context` の反映には reload / 再起動が必要**。

> **key-store の `path` は `javax.net.ssl.trustStore` と同じファイルにすること。**
> `default-ssl-context` を設定すると、JVM 既定の SSLContext は `javax.net.ssl.trustStore` ではなく
> この client-ssl-context の trust-manager から構築される。両者が別ファイルを指していると
> 「トラストストアに import したのに HTTPS が繋がらない」状態になる。
> `GET /api/tls/config` の `elytron.key-store` はこの不一致を検出して `detail` に出力する。

### 6.2 CLI での確認

```bash
$JBOSS_HOME/bin/jboss-cli.sh --connect --commands="\
/subsystem=elytron/key-store=cacertTrustStore:read-resource,\
/subsystem=elytron/trust-manager=cacertTrustManager:read-resource,\
/subsystem=elytron/client-ssl-context=cacertClientSslContext:read-resource,\
/subsystem=elytron:read-attribute(name=default-ssl-context)"

# トラストストア内のエイリアスを elytron 経由で一覧する
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  --command="/subsystem=elytron/key-store=cacertTrustStore:read-aliases"
```

同じ内容を「実際に通信する JVM の内側」から確認するのが `GET /api/tls/config`。
CLI は設定ファイルの状態を、REST API は**実行中の JVM に反映された状態**を見るため、
`:reload` 忘れなどは REST API 側だけが検出できる。

---

## 7. curl での確認（Java アプリを使わない）

**curl は JKS / PKCS12 のトラストストアを直接読めない。**
そのため `keytool -list -rfc` でトラストストアから PEM バンドルを書き出し、`--cacert` に渡す。

### 7.1 シェルスクリプト

```bash
chmod +x scripts/verify-truststore-curl.sh

# 最小: URL だけ指定（トラストストアは起動中の JBoss プロセスの
#       -Djavax.net.ssl.trustStore / -Djavax.net.ssl.trustStorePassword から自動検出）
./scripts/verify-truststore-curl.sh -u https://example.internal:8443/health

# 明示指定（cacert.crt を渡すとストア内との一致も検証する）
./scripts/verify-truststore-curl.sh \
  -u https://example.internal:8443/health \
  -t /opt/jboss/certs/truststore.jks \
  -p "$TRUSTSTORE_PASSWORD" \
  -c /opt/jboss/certs/cacert.crt
```

| オプション | 環境変数 | 内容 |
|---|---|---|
| `-u, --url` | `TARGET_URL` | 接続先（https 必須） |
| `-t, --truststore` | `TRUSTSTORE_PATH` | トラストストア。省略時は JBoss プロセスから自動検出 |
| `-p, --password` | `TRUSTSTORE_PASSWORD` | トラストストアのパスワード。省略時は同上 |
| `-c, --cacert` | `CACERT_FILE` | 自己署名証明書 `cacert.crt` |
| `-k, --keytool` | `KEYTOOL_BIN` | keytool のパス（省略時は PATH / `JAVA_HOME` から探す） |
| `--connect-timeout` | `CONNECT_TIMEOUT` | curl の接続タイムアウト秒（既定 10） |
| `--max-time` | `MAX_TIME` | curl の最大実行秒（既定 30） |

実行するテスト:

| # | テスト | 判定 |
|---|---|---|
| 1 | トラストストアファイルを読み取れる | FAIL なら以降中止 |
| 2 | `keytool -list -rfc` で PEM バンドルを書き出せる | 証明書 1 枚以上で PASS |
| 3 | `cacert.crt` と同一の証明書がストア内にある（SHA-256 比較） | `-c` 指定時のみ |
| 4 | `curl --cacert <ストア由来 PEM>` で接続成功 | **本題** |
| 5 | `curl --cacert cacert.crt` で接続成功 | `-c` 指定時のみ |
| 6 | **`--cacert` 無しでは失敗すること**（curl exit 60） | 成功してしまう場合は WARN |
| 7 | `openssl s_client -CAfile ...` で `Verify return code: 0 (ok)` | openssl があるときのみ |

テスト 6 が重要で、ここで失敗（exit 60）することによって、テスト 4/5 の成功が
「OS 標準の CA バンドルではなく、**渡した自己署名証明書のおかげ**」だと確定できる。

終了コードは全テスト成功で `0`、FAIL があれば `1`。

> **実行環境は JBoss EAP が動く Linux ホストを想定している。**
> Windows の Git Bash 上で試すと、同梱の `curl.exe` が TLS バックエンドに schannel（Windows の
> 証明書ストア）を使うため `--cacert` に渡した PEM が無視され、`/tmp/...` のような MSYS パスも
> 解決できずテスト 4/5 が失敗する。keytool による PEM 書き出し（テスト 2）や
> `openssl s_client`（テスト 7）は Windows でも動作する。

実行例:

```
=== 0. トラストストアの特定 ===
     起動中の JBoss EAP プロセスを検出した。
     -Djavax.net.ssl.trustStore を検出: /opt/jboss/certs/truststore.jks
     -Djavax.net.ssl.trustStorePassword を検出（値は表示しない）

=== 1. トラストストアファイル ===
[PASS] トラストストアを読み取れる: /opt/jboss/certs/truststore.jks

=== 2. トラストストアから PEM バンドルを書き出す (keytool -rfc) ===
[PASS] PEM バンドルを書き出した（証明書 1 枚） -> /tmp/tmp.XXXX/truststore-bundle.pem

=== 3. cacert.crt がトラストストアに登録されているか ===
     cacert.crt SHA-256: 3B:1F:...:9C
[PASS] cacert.crt と同一の証明書がトラストストアに登録されている。

=== 4. curl でトラストストア由来の PEM を使って HTTPS 接続 ===
[PASS] curl --cacert <トラストストア由来 PEM> で接続成功（HTTP 200）。

=== 6. 対照テスト: 自己署名証明書を渡さないと失敗すること ===
[PASS] --cacert 無しでは検証に失敗した（curl exit 60）。自己署名証明書が効いていることの裏付け。

=== 結果 ===
  PASS=6  FAIL=0  WARN=0  SKIP=0
判定: OK — トラストストアの自己署名証明書で curl から HTTPS 接続できている。
```

### 7.2 手作業で確認する場合

```bash
# 1) トラストストア -> PEM バンドル
keytool -list -rfc -keystore /opt/jboss/certs/truststore.jks -storepass "$TRUSTSTORE_PASSWORD" \
  | awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' > /tmp/truststore-bundle.pem

# 2) その PEM で接続
curl -v --cacert /tmp/truststore-bundle.pem https://example.internal:8443/health

# 3) cacert.crt を直接指定しても同じ
curl -v --cacert /opt/jboss/certs/cacert.crt https://example.internal:8443/health

# 4) 対照テスト: 指定しなければ失敗する（curl: (60) SSL certificate problem）
curl -v https://example.internal:8443/health ; echo "exit=$?"

# 5) 環境変数で渡す方法（--cacert と同等）
CURL_CA_BUNDLE=/opt/jboss/certs/cacert.crt curl -v https://example.internal:8443/health

# 6) openssl での確認
openssl s_client -connect example.internal:8443 -servername example.internal \
  -CAfile /opt/jboss/certs/cacert.crt </dev/null 2>/dev/null | grep 'Verify return code'
```

curl の主な終了コード:

| exit | 意味 | 対処 |
|---|---|---|
| `60` | サーバ証明書を検証できない | `--cacert` に渡した PEM に cacert.crt が含まれているか確認 |
| `51` | ホスト名が証明書と一致しない | 証明書の SAN / CN と URL のホスト名を合わせる |
| `35` | TLS ハンドシェイク失敗 | プロトコル・暗号スイートの不一致を確認 |
| `7` | 接続不可 | ホスト・ポート・経路（SG / FW） |
| `6` | 名前解決失敗 | DNS / hosts |

> **`-k` / `--insecure` は使わないこと。** 検証を無効化するため、
> 「トラストストアの証明書で検証できたか」の確認にならない。

---

## 8. トラブルシューティング

| 症状 | 原因 | 対処 |
|---|---|---|
| `TLS_HANDSHAKE_FAILED` / `PKIX path building failed` | cacert.crt がトラストストアに未登録 | `keytool -importcert` で登録し、AP サーバを再起動 |
| curl は成功するのに REST API は失敗する | elytron の `default-ssl-context` が別のトラストマネージャを指している | `GET /api/tls/config` の `elytron.key-store` の `detail` を確認。key-store の `path` を `javax.net.ssl.trustStore` と揃える |
| REST API は成功するのに curl は失敗する | curl に渡した PEM が古い／別のストアから書き出した | `keytool -list -rfc` をやり直す |
| `No subject alternative names` / `HTTPS hostname wrong` | 証明書の SAN と URL のホスト名が不一致 | SAN 付きで証明書を作り直すか、URL のホスト名を証明書に合わせる |
| `jvm.truststore.property` が NG | 起動パラメータが渡っていない | `standalone.conf` の `JAVA_OPTS` を確認。`ps -ef \| grep standalone` でも確認できる |
| `elytron.*` が全部 `UNKNOWN` | jmx サブシステムが無効で管理モデルを読めない | jboss-cli で直接確認する（[6.2](#62-cli-での確認)）。アプリから読ませるには standalone.xml の jmx サブシステム（`expose-resolved-model`）を有効化 |
| `elytron.default-ssl-context` は OK なのに `jvm.default-ssl-context` が UNKNOWN | 設定後に reload していない／プロバイダ名がバージョン差で判定できない | `:reload` 後に `GET /api/tls/config?probe=true` で実通信確認 |
| `jvm.truststore.file` が「パスワード無しで読み取った」と出る | `-Djavax.net.ssl.trustStorePassword` の値が実際のストアと違う | パスワードを修正する（誤っていても検証自体は動くが、整合性チェックが効かない） |
| トラストストアを更新したのに反映されない | JSSE は起動時にトラストストアを読み込む | AP サーバを再起動する |

---

## 9. ログ

他の API と同様、処理内容は `${LOG_OUT_DIR}/<IP>_application.log` ほかへ出力される
（`com.example.dhapp` ロガー、DEBUG まで）。TLS 関連では次が記録される。

```
TLS call start. requestId=..., url=..., host=..., port=..., trustStore=/opt/jboss/certs/truststore.jks, sslContextProvider=SunJSSE
TLS call done.  requestId=..., status=SUCCESS, httpStatus=200, tlsProtocol=TLSv1.3, cipherSuite=..., verifiedByTrustStore=true, trustAnchorAlias=cacert, elapsedMs=96
TLS config check done. requestId=..., status=OK, ok=10, ng=0, unknown=0, probe=false, elapsedMs=31
```

ハンドシェイク失敗時は `error.log` にスタックトレースが出る。
JSSE 側の詳細を見たい場合は起動パラメータに `-Djavax.net.debug=ssl:handshake:trustmanager` を追加する
（出力量が非常に多いので調査時のみ）。
