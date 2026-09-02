# dhapp — Spring Boot WAR on WildFly with JTA/XA 2PC

`POST /api/demo/execute` で以下を 1 リクエストで実行する。

1. ElastiCache for Valkey にダミーセッションを保存
2. DHCOMAP に 1 件 INSERT
3. DHINFAP に 1 件 INSERT
4. 2,3 を JTA/XA の 2 フェーズコミットで実行（片方失敗で両方ロールバック）
5. 設定可能な外部 REST API を HTTP POST で呼び出す

## ビルド

```
mvn -DskipTests clean package
# → target/dhapp.war
```

## コンテナ実行

コンテキストパスは `/iwinmichl` なので、エンドポイントは `http://host:8080/iwinmichl/api/demo/execute`。

## MySQL 8.4 / Connector/J 9.x で 2PC を動かすための必須設定

MySQL 8.4.7 + Connector/J 9.7.0 の組み合わせでは、**WildFly の XA データソースに
`same-rm-override=false` を設定しないと 2PC が必ず失敗する**。設定が無いと以下の例外が出る。

```
java.sql.SQLException: jakarta.resource.ResourceException:
    IJ000457: Unchecked throwable in managedConnectionReconnected()
java.sql.SQLException: XAER_INVAL: Invalid arguments (or unsupported command)
```

### 原因

1. Connector/J **9.5.0** で `XAResource.isSameRM()` の判定が変更された（Bug #18403804）。
   それまで比較していた**スキーマ名が比較対象から外れ、ホストとポートだけ**で同一リソース
   マネージャか判定するようになった。
2. DHCOMAP と DHINFAP が同じ MySQL インスタンス上にあると、2 つの XA データソースが
   `isSameRM() == true` と判定される。
3. WildFly のトランザクションマネージャ(Narayana)は「同じ RM ならブランチを結合できる」と
   判断し、2 本目の enlist で `XAResource.start(xid, TMJOIN)` を呼ぶ。
4. Connector/J は `XA START <xid> JOIN` を送信するが、**MySQL は JOIN / RESUME を
   サポートしていない**ため `ERROR 1398 (XAE05) XAER_INVAL` を返す。
5. その `XAException` が IronJacamar の `enlistResource()` から抜け、
   `IJ000457: Unchecked throwable in managedConnectionReconnected()` になる。

つまり 2 つの例外は同一原因で、Connector/J 8.4.0 では動いていたものが 9.5.0 以降で
表面化する。JOIN 非対応は MySQL サーバ側の仕様であり `my.cnf` では変更できないため、
**修正はデータソース設定側で行う**。

### 修正内容

| 対象 | 設定 | 理由 |
|---|---|---|
| WildFly XA DS（両方） | `same-rm-override=false` | **本命の修正**。`isSameRM()` を常に false にしてブランチ結合を抑止し、別ブランチ（同一 gtrid・別 bqual）として 2PC させる |
| WildFly XA DS（両方） | `no-tx-separate-pool=true` | 起動時 DDL（`SchemaInitializer`）などトランザクション外の利用とトランザクション内の利用で物理プールを分ける |
| WildFly XA DS（両方） | `PinGlobalTxToPhysicalConnection` を**削除** | Connector/J 8/9 の `MysqlXADataSource` にセッターが無く適用されない。有効になると `SuspendableXAConnection`（Xid→物理コネクションの static Map）が使われ JCA プールと二重管理になる。JOIN 問題も解決しない |
| MySQL | `GRANT XA_RECOVER_ADMIN ON *.* TO ...` | MySQL 8.0 以降 `XA RECOVER` に必要。無いと WildFly の periodic recovery が in-doubt ブランチを回収できない |
| MySQL | `xa_detach_on_prepare=ON`（8.0.29 以降の既定のまま） | `XA PREPARE` 後にブランチをセッションから切り離す。コネクションプール／リカバリと相性が良い |

適用スクリプトはリポジトリに同梱している。

```
# 既存サーバへ修正だけを適用（適用後 reload される）
$JBOSS_HOME/bin/jboss-cli.sh --connect --file=wildfly/fix-xa-2pc.cli

# 新規構築（ドライバ登録 + XA データソース 2 つ）
$JBOSS_HOME/bin/jboss-cli.sh --file=wildfly/register-mysql-driver.cli
$JBOSS_HOME/bin/jboss-cli.sh --file=wildfly/configure-wildfly.cli

# MySQL 側（スキーマ・ユーザー・XA_RECOVER_ADMIN）
mysql -h <host> -u root -p < mysql/init-xa.sql
```

`standalone.xml` を直接編集する場合は、各 `<xa-datasource>` に次を追加する。

```xml
<xa-pool>
    <no-tx-separate-pools>true</no-tx-separate-pools>
</xa-pool>
<is-same-rm-override>false</is-same-rm-override>
```

### 起動時の自己診断

`XaSelfCheck` が起動時に、1 つの JTA トランザクション内で DHCOMAP / DHINFAP の両方を
enlist できるかを `SELECT 1` だけで検証し、必ずロールバックする（データは変更しない）。
失敗した場合は原因と対処コマンドを ERROR ログに出力する（起動自体は止めない）。
不要なら `app.xa.self-check.enabled=false` で無効化できる。

```
[2PC][self-check] OK. Both DHCOMAP and DHINFAP were enlisted as separate XA branches in a single JTA transaction.
```

XA コマンド自体を確認したい場合は、JDBC URL に `&logXaCommands=true` を付けると
`XA START` / `XA END` / `XA PREPARE` / `XA COMMIT` がドライバのログに出る。

### 補足

- 2 つの DB が**同一 MySQL インスタンス上の別スキーマ**なら、本来 XA は不要
  （1 本のローカルトランザクションで両スキーマへ INSERT すれば原子性は確保できる）。
  本アプリは 2PC の動作確認が目的なので XA を使っている。
- 2 つの DB が**別ホスト**なら `isSameRM()` は false になるため、この問題は起きない。
- XA/2PC は RDS Proxy 経由では正しく動作しない。XA データソースの接続先は
  Aurora の Writer エンドポイントを直接指定すること。
- MySQL 8.4 では `mysql_native_password` が既定で無効。既定の `caching_sha2_password` を
  使い、非 TLS 接続の場合のみ JDBC URL に `allowPublicKeyRetrieval=true` を付ける。

## API 一覧

| API | パス | 内容 |
|---|---|---|
| 統合 | `POST /api/demo/execute` | Valkey 保存 → DB 2PC → 外部 API を一括実行 |
| DB のみ | `POST /api/db/execute` | DHCOMAP/DHINFAP への 2PC INSERT のみ（`failMode` でロールバック検証可） |
| ElastiCache のみ | `POST /api/cache/execute` | Valkey へ保存し、読み戻した内容を返す |
| 外部 API のみ | `POST /api/external/execute` | 設定 URL へ HTTP POST し結果を返す |
| ファイルアップロード | `POST /api/file/upload` | multipart で受け取ったファイルを AP サーバのテンポラリフォルダへ保存し、保存場所とサイズをログ・レスポンスに出力 |
| アップロード設定確認 | `GET /api/file/upload-info` | 保存先テンポラリフォルダと適用中のサイズ上限を返す |
| HTTPS 通信（自己署名証明書） | `POST /api/tls/call` | 指定 URL へ、JVM トラストストアの `cacert.crt` で検証しながら HTTPS 通信する（`GET /api/tls/call?url=...` も可） |
| TLS 設定確認 | `GET /api/tls/config` | トラストストア／トラストマネージャー／クライアント SSL コンテキスト／JVM 既定 SSL コンテキストの設定を確認する |
| 設定ファイル読み込み確認 | `GET /api/config/date-config` | `date_config.properties` を**ファイル読み**（`/webapp/webapp9mf02/serverlets/...`）と**リソース読み**（war 同梱のクラスパス配下）の 2 経路で読み、結果をログ・コンソールへ出力して比較する。deployment-overlay の反映も検知する |
| secure-api への HTTPS 接続確認 | `GET /api/secure-api/call` | **JVM 管理**と **JBoss EAP(Elytron) 管理**の各トラストストアで compose の `secure-api` へ HTTPS 接続し、結果を詳細に画面表示・ログ出力して比較する |
| トラストストア内容確認 | `GET /api/secure-api/truststores` | 接続せず、JVM 側・JBoss EAP 側それぞれのトラストストアの中身と elytron の登録状態を返す |
| エラーログ検証 | `POST /api/log/error-test` | ネストした例外を `error.log` に出力する（HTTP 500 にはしない） |

リクエストボディは JSON 系 API 共通（`sessionId`, `userId` は必須。`message` は任意。`failMode` は DB のみ有効）。
ファイルアップロード API のみ `multipart/form-data` で受け取る（**詳細は [FILE_UPLOAD_API.md](FILE_UPLOAD_API.md)**）。
TLS 系 API のリクエストボディは独自形式（**詳細は [TLS_SELFSIGNED_API.md](TLS_SELFSIGNED_API.md)**）。
設定ファイル読み込み確認 API はクエリパラメータのみ（**詳細は [CONFIG_READ_API.md](CONFIG_READ_API.md)**）。
secure-api への HTTPS 接続確認 API もクエリパラメータのみ（**詳細は [SECURE_API_TLS.md](SECURE_API_TLS.md)**）。

## 動作確認

統合（全部）:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/demo/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"dummy-session-001","userId":"user-001","message":"hello"}'
```

DB のみ:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/db/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"db-001","userId":"user-001","message":"hello"}'
```

ElastiCache のみ（stored に読み戻し結果が入る）:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/cache/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"cache-001","userId":"user-001","message":"hello"}'
```

外部 API のみ:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/external/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"ext-001","userId":"user-001","message":"hello"}'
```

2PC ロールバック検証（DB のみ API で。両 DB に INSERT されないこと）:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/db/execute \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"rb-001","userId":"user-001","message":"hello","failMode":"AFTER_DHINFAP"}'
```

ファイルアップロード（保存先の絶対パスと保存サイズがレスポンスとログに出る）:
```
# 保存先テンポラリフォルダと適用中の上限を確認
curl -i http://localhost:8080/iwinmichl/api/file/upload-info

# アップロード（パート名は file 固定。-F の @ でファイルを指定する）
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin" \
  -F "note=upload test"
```

curl でのファイル指定方法のバリエーション、レスポンス全項目の説明、`max-post-size` 超過時の
詳細レスポンスは **[FILE_UPLOAD_API.md](FILE_UPLOAD_API.md)** にまとめている。

自己署名証明書での HTTPS 通信（TLS ハンドシェイクの内容と証明書チェーンが返る）:
```
curl -i -X POST http://localhost:8080/iwinmichl/api/tls/call \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.internal:8443/health"}'
```

トラストストア・elytron の設定確認（`?probe=true` で実通信まで確認）:
```
curl -s http://localhost:8080/iwinmichl/api/tls/config | jq '{status, okCount, ngCount, unknownCount}'
```

`date_config.properties` の読み込み確認（ファイル読み vs リソース読み。結果はログとコンソールにも出る）:
```
# JSON（全項目）
curl -s http://localhost:8080/iwinmichl/api/config/date-config | jq .

# ログ・コンソールと同じテキストレポート
curl -s "http://localhost:8080/iwinmichl/api/config/date-config?format=text"

# 要点だけ（比較結果と deployment-overlay の反映有無）
curl -s http://localhost:8080/iwinmichl/api/config/date-config \
  | jq '{status, verdict: .comparison.verdict,
         overlay: .deploymentOverlay.dateConfigOverlayDefined,
         changed: .deploymentOverlay.resourceContentChanged}'
```

secure-api への HTTPS 接続確認（JVM 管理ストアと JBoss EAP 管理ストアの両方で接続して比較）:
```
# 2 系統で接続（テキストレポートは画面表示用）
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?format=text"

# 要点だけ
curl -s http://localhost:8080/iwinmichl/api/secure-api/call \
  | jq '{status, jvm: .comparison.jvmStatus, jboss: .comparison.jbossStatus,
         both: .comparison.bothSucceeded}'

# ALB 経由 / 対照実験（空のトラストストア）込み
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?target=alb"
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?trust=all"

# 接続せずトラストストアの中身だけ確認（切り分け用）
curl -s "http://localhost:8080/iwinmichl/api/secure-api/truststores?format=text"
```

## ファイルアップロード

`POST /api/file/upload` は multipart で受け取ったファイルを **AP サーバのテンポラリフォルダ**へ保存し、
**保存場所（絶対パス）と保存したファイルのサイズ**をログとレスポンスに出力する。

保存先は `spring.servlet.multipart.location` が未設定なら AP サーバがデプロイに割り当てた
テンポラリフォルダ（ServletContext の `jakarta.servlet.context.tempdir`。WildFly では
`$JBOSS_HOME/standalone/tmp/` 配下）。実際の値は `GET /api/file/upload-info` で確認できる。

サイズ上限は 2 段あり、超過時はどちらも HTTP 413 と詳細な JSON（`limitSource` でどちらの上限かを判別）を返す。

| 上限 | 設定 | 既定 |
|---|---|---|
| アプリ側 | `MULTIPART_MAX_FILE_SIZE` / `MULTIPART_MAX_REQUEST_SIZE` | 5MB |
| AP サーバ側 | WildFly http-listener の `max-post-size` | 10MB |

アプリ側を AP サーバ側より小さくしておくと、上限超過が必ずアプリ側で検知され詳細な JSON を返せる
（既定値はこの関係）。

| 環境変数 | 既定値 | 内容 |
|---|---|---|
| `MULTIPART_MAX_FILE_SIZE` | `5MB` | 1 ファイルあたりの上限 |
| `MULTIPART_MAX_REQUEST_SIZE` | `5MB` | マルチパートリクエスト全体の上限 |
| `MULTIPART_FILE_SIZE_THRESHOLD` | `0B` | ディスク退避の閾値 |
| `MULTIPART_LOCATION` | （空） | 保存先の明示指定。空なら AP サーバ既定のテンポラリフォルダ |

利用方法、curl でのアップロードファイル指定方法、レスポンス全項目の説明、`max-post-size` 超過時の
詳細レスポンスは **[FILE_UPLOAD_API.md](FILE_UPLOAD_API.md)** を参照。

## 自己署名証明書 (cacert.crt) による HTTPS 通信

`POST /api/tls/call` は、**JVM のトラストストアに登録された自己署名証明書 `cacert.crt`** で
サーバ証明書を検証しながら、指定 URL へ HTTPS 通信する。独自のトラストマネージャは組み立てず
**JVM 既定の SSLContext だけ**を使うため、次のどちらの登録が効いているかがそのまま結果に現れる
（検証を無効化するオプションは用意していない）。

- standalone 起動パラメータ `-Djavax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStorePassword`
- jboss-cli で登録した elytron の `default-ssl-context`（設定されているとこちらが優先される）

`GET /api/tls/config` は、その登録が **実行中の JVM に反映されているか**を確認する。

| 確認対象 | 対応する設定 |
|---|---|
| JVM トラストストア | `-Djavax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStorePassword` と、そこへの cacert.crt の登録 |
| トラストマネージャー | `/subsystem=elytron/trust-manager=cacertTrustManager` |
| クライアント SSL コンテキスト | `/subsystem=elytron/client-ssl-context=cacertClientSslContext` |
| JVM 既定 SSL コンテキスト | `/subsystem=elytron:write-attribute(name=default-ssl-context, ...)` |

elytron への登録は同梱の CLI スクリプトで行う（`default-ssl-context` の反映には reload / 再起動が必要）。

```
TRUSTSTORE_PATH=/opt/jboss/certs/truststore.jks \
TRUSTSTORE_PASSWORD=<password> \
  $JBOSS_HOME/bin/jboss-cli.sh --file=wildfly/configure-truststore.cli
```

Java アプリを介さず **curl だけで**同じ証明書を使って接続できることは、同梱スクリプトで確認できる
（curl は JKS を直接読めないため、keytool でトラストストアから PEM を書き出して `--cacert` に渡す）。

```
./scripts/verify-truststore-curl.sh -u https://example.internal:8443/health \
  -t /opt/jboss/certs/truststore.jks -p "$TRUSTSTORE_PASSWORD" -c /opt/jboss/certs/cacert.crt
```

レスポンス全項目の説明、チェック項目一覧、JBoss CLI での登録・確認コマンド、curl での確認手順は
**[TLS_SELFSIGNED_API.md](TLS_SELFSIGNED_API.md)** を参照。

## 設定ファイルの読み込み確認（ファイル読み / リソース読み / deployment-overlay）

`GET /api/config/date-config` は、同じ `date_config.properties` を **2 つの経路**で読み込み、
その内容をログ・コンソールへ出力したうえで比較する。

| 経路 | 対象 | 読み方 |
|---|---|---|
| ファイル読み | `/webapp/webapp9mf02/serverlets/jp/iwin/base/tango/date_config.properties`（war の外） | `Files.readAllBytes()` |
| リソース読み | クラスパス配下の `jp/iwin/base/tango/date_config.properties`（war 同梱 → `WEB-INF/classes/`） | `ClassLoader#getResource()` |

war 同梱側は `src/main/resources/jp/iwin/base/tango/date_config.properties` としてリポジトリに含めてあり、
`mvn package` でそのまま war のアーカイブ対象になる。ファイル読み側のサンプルは
`samples/webapp/` に同じ階層で置いてある（`cp -r samples/webapp /` で配置できる）。

比較結果は `comparison.verdict` に出る（`IDENTICAL` / `SAME_PROPERTIES` / `DIFFERENT` /
`FILE_ONLY` / `RESOURCE_ONLY` / `BOTH_UNAVAILABLE`）。差分があるキーは
`comparison.differentValues` に `file=… / resource=…` の形で並ぶ。

**deployment-overlay による差し替えの反映**も 2 つの方法で検知する。

1. 管理モデル（JMX ファサード `jboss.as:deployment-overlay=*`）から overlay の定義・
   リンク先デプロイメント・`content-hash` を読む（設定として存在するか）
2. 読み取った内容の指紋（解決先 URL・SHA-256）を前回の呼び出しと比較する（実際に差し替わったか）

```
# overlay 適用前に一度呼んで指紋を記録 → overlay 適用 → もう一度呼ぶ
curl -s http://localhost:8080/iwinmichl/api/config/date-config > /dev/null
cp samples/overlay/date_config.properties /opt/overlay/date_config.properties
$JBOSS_HOME/bin/jboss-cli.sh --connect --file=wildfly/configure-date-config-overlay.cli
curl -s http://localhost:8080/iwinmichl/api/config/date-config \
  | jq '{overlay: .deploymentOverlay.dateConfigOverlayDefined,
         changed: .deploymentOverlay.resourceContentChanged}'
# → { "overlay": true, "changed": true }
```

レスポンス全項目の説明、確認手順、設定一覧は **[CONFIG_READ_API.md](CONFIG_READ_API.md)** を参照。

## secure-api への HTTPS 接続確認（JVM / JBoss EAP の各トラストストア）

`GET /api/secure-api/call` は、**JVM が管理するトラストストア**と
**JBoss EAP(Elytron) が管理するトラストストア**のそれぞれで、compose の `secure-api` サービス
（別リポジトリ `Container_Compose_file`。WireMock を `--disable-http` で起動した HTTPS 必須の API）へ
接続し、TLS ハンドシェイクの内容と HTTP 応答を詳細に画面表示・ログ出力する。

| trustSource | トラストストアの実体 | SSLContext |
|---|---|---|
| `JVM` | `-Djavax.net.ssl.trustStore` が指すストア | `SSLContext.getDefault()`（アプリは何も設定しない） |
| `JBOSS_EAP` | elytron の `key-store`（例 `appTrustStore` → `$JBOSS_HOME/standalone/configuration/jboss-truststore.p12`） | そのファイルから組み立てた専用 SSLContext |
| `NONE` | 空のトラストストア（`trust=all` のときだけ実行する対照実験） | 失敗するのが正しい |

JBoss EAP 側ストアの位置は決め打ちせず、`app.secure-api.jboss.truststore-path` →
elytron の `key-store` の `path` / `relative-to`（JMX 管理モデルから取得）→
`${jboss.server.config.dir}/jboss-truststore.p12` の順に解決する。どれが使われたかは
レスポンスの `trustStoreResolution` に出る。

接続先の既定値は compose の環境変数に合わせてある
（`SECURE_API_URL=https://secure-api:8443/api/v1/ping`、
`SECURE_API_VIA_ALB_URL=https://alb/secure/v1/ping` → `?target=alb`）。

```
curl -s "http://localhost:8080/iwinmichl/api/secure-api/call?format=text"
curl -s http://localhost:8080/iwinmichl/api/secure-api/call \
  | jq '{status, jvm: .comparison.jvmStatus, jboss: .comparison.jbossStatus,
         both: .comparison.bothSucceeded, summary: .comparison.summary}'
```

どちらか一方だけ失敗した場合は、失敗した側のストアへの `cacert.crt` の取り込みが効いていない。
`GET /api/secure-api/truststores` で両ストアの中身（エイリアス・エントリ数）と elytron の
登録状態を確認できる。判定の読み方・レスポンス全項目・設定一覧は
**[SECURE_API_TLS.md](SECURE_API_TLS.md)** を参照。

## ログ出力

各機能（demo / db / cache / external / file / tls / config / secure-api）は、REST API で処理した内容
（リクエスト内容・処理ステップ・レスポンス・処理時間）を詳細にログへ出力する。ファイルアップロード
API では**保存先の絶対パスと保存したファイルのサイズ**が INFO で出力される。出力先・フォーマットは
`src/main/resources/logback-spring.xml` で構成し、出力ルートは **環境変数 `LOG_OUT_DIR`** で指定する。

`GET /api/config/date-config` と `GET /api/secure-api/call` は、結果のテキストレポートを
**ログと同時にコンソール（標準出力）へも直接出力する**。コンソールへの出力は UTF-8 固定で書くため、
コンテナのロケールが `POSIX` / `C`（`stdout.encoding` が ASCII）でも日本語が化けない。
同じレポートはレスポンスの `report` フィールドと `?format=text` からも取得できる。

> WildFly(JBoss EAP) デプロイ時は `jboss-deployment-structure.xml` で logging サブシステムを除外して
> いるため、ログ出力は war 内の Logback（Spring Boot 標準）が担う。

| ファイル | パス | 内容 |
|---|---|---|
| アプリログ | `${LOG_OUT_DIR}/application.log` | 各 REST API 機能の処理内容を DEBUG まで詳細に記録 |
| エラーログ | `${LOG_OUT_DIR}/error.log` | ERROR のみ。Java 例外スタックトレース形式（CloudWatch マルチライン検証用） |
| サーバログ | `${LOG_OUT_DIR}/mid/server.log` | JBoss EAP のサーバログ相当（EAP 既定フォーマット・フレームワーク含む全体） |

各 REST API（demo / db / cache / external / file）の処理内容は、上記に加えて以下のファイルにも
**すべて同じ内容**で必ず出力される（`application.log` と同じ処理内容ログ）。`mid` を挟むものは
`mid` ディレクトリ配下に出力する（ディレクトリは自動作成）。

| ファイル | パス |
|---|---|
| keax0003.log | `${LOG_OUT_DIR}/keax0003.log` |
| xxxxxxxxxx.err | `${LOG_OUT_DIR}/xxxxxxxxxx.err` |
| accesslog | `${LOG_OUT_DIR}/accesslog` |
| tracelog | `${LOG_OUT_DIR}/tracelog` |
| dbiolog | `${LOG_OUT_DIR}/dbiolog` |
| inputmsglog | `${LOG_OUT_DIR}/inputmsglog` |
| outputmsglog | `${LOG_OUT_DIR}/outputmsglog` |
| asyncdriver.log | `${LOG_OUT_DIR}/asyncdriver.log` |
| authlog | `${LOG_OUT_DIR}/authlog` |
| connectinlog | `${LOG_OUT_DIR}/connectinlog` |
| connectoutlog | `${LOG_OUT_DIR}/connectoutlog` |
| asyncdriver_xxxxx.err | `${LOG_OUT_DIR}/asyncdriver_xxxxx.err` |
| gc.log | `${LOG_OUT_DIR}/mid/gc.log` |

さらに、環境変数 **`DATA_OUTPUT_DIR`** で指定したデータ出力ディレクトリに、各 REST API
（demo / db / cache / external / file / log）の呼び出しごとに `dummy.pdf` を生成する（未設定時は `./data`）。
この `dummy.pdf` はログのテキストではなく、**Apache PDFBox で生成した本物の PDF**（`DUMMY` という
文字列を記載）で、`com.example.dhapp.service.DummyPdfService` が呼び出しのたびに上書き作成する。
ディレクトリが無い場合は自動作成する。

| ファイル | パス | 内容 |
|---|---|---|
| dummy.pdf | `${DATA_OUTPUT_DIR}/dummy.pdf` | PDFBox 生成の PDF（`DUMMY` を記載） |

```
# Linux/WildFly
export DATA_OUTPUT_DIR=/var/data/dhapp
```

`LOG_OUT_DIR` 未設定時はカレントディレクトリ配下 `./logs` を使う。指定例:

```
# Linux/WildFly
export LOG_OUT_DIR=/var/log/dhapp
```

### error.log（CloudWatch Agent マルチライン検証）

`error.log` には ERROR レベルのログのみが、標準の Java 例外スタックトレース形式
（`Caused by:` / `... N more` を含む複数行）で出力される。各エントリは必ず先頭がタイムスタンプで
始まるため、CloudWatch Agent 側で次のように設定すればスタックトレース全体を 1 イベントとして
扱えることを確認できる。

```
[/var/log/dhapp/error.log]
multi_line_start_pattern = "^\d{4}-\d{2}-\d{2}"
```

検証用に、意図的にネストした例外（Caused by を 2 段含む）を error.log へ出力するテスト API を用意している
（HTTP 500 にはならず、error.log への書き込みのみを行う）:

```
# 1 件出力
curl -i -X POST http://localhost:8080/iwinmichl/api/log/error-test

# 複数件（区切り確認用。最大 100）
curl -i -X POST "http://localhost:8080/iwinmichl/api/log/error-test?count=5"
```

なお、DB API の 2PC ロールバック検証（`failMode`）でも `DemoException` のスタックトレースが
`error.log` に出力される。

### server.log

`${LOG_OUT_DIR}/mid/server.log` は JBoss EAP の standalone server.log 既定フォーマット
（`%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n` 相当）で、アプリだけでなく
Spring/WildFly 由来のログを含む全体を記録する。`mid` ディレクトリは自動作成される。
