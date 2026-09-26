# date_config.properties 読み込み確認 API（ファイル読み / リソース読み）

`GET /api/config/date-config` は、同じ `date_config.properties` を **2 つの経路**で読み込み、
その結果をログ・コンソールへ出力したうえで比較する。あわせて JBoss EAP の
**deployment-overlay による差し替えが反映されたか**も検知する。

| 経路 | 対象 | 読み方 |
|---|---|---|
| **ファイル読み** | `/webapp/webapp9mf02/servlets/jp/iwin/base/tango/date_config.properties`（war の外・AP サーバのファイルシステム上） | `Files.readAllBytes()` |
| **リソース読み** | クラスパス配下の `jp/iwin/base/tango/date_config.properties`（war にアーカイブ済み → `WEB-INF/classes/` 配下） | `ClassLoader#getResource()` |

コンテキストパスは `/iwinmichl` なので、実際の URL は次のようになる。

```
http://localhost:8080/iwinmichl/api/config/date-config
```

## エンドポイント

| メソッド | パス | 内容 |
|---|---|---|
| `GET` | `/api/config/date-config` | 2 経路で読み込み、結果・比較・overlay 検知を JSON で返す |
| `POST` | `/api/config/date-config` | 同上（他 API と呼び出し方を揃えるための POST 版） |
| `GET` | `/api/config/date-config?format=text` | ログ・コンソールと同じテキストレポートをそのまま返す |

読み込みに失敗しても HTTP 500 にはせず **200 + `status`** で返す（「読めなかったこと」自体が
確認したい結果のため。TLS 系 API と同方針）。

## 事前準備

### 1. ファイル読み側（war の外）

サンプルをそのままの階層で配置する。

```
cp -r samples/webapp /
chown -R jboss:jboss /webapp        # EAP の実行ユーザーが読めるようにする
```

別の場所に置く場合は環境変数 `DATE_CONFIG_FILE_PATH` で実際のパスを指定する。

### 2. リソース読み側（war 同梱）

`src/main/resources/jp/iwin/base/tango/date_config.properties` として**すでにリポジトリに含まれている**。
`mvn package` すると war のアーカイブ対象になり、デプロイ後は
`WEB-INF/classes/jp/iwin/base/tango/date_config.properties` に展開される。

```
# war に入っていることの確認
unzip -l target/dhapp.war | grep date_config
```

両者は `config.source` / `config.origin` / `config.revision` を意図的に変えてあるので、
レスポンスの `comparison.differentValues` で「どちらの経路で読んだ値か」がそのまま分かる。

## 動作確認

```
# JSON（全項目）
curl -s http://localhost:8080/iwinmichl/api/config/date-config | jq .

# 画面表示用のテキストレポート（ログ・コンソール出力と同じ内容）
curl -s "http://localhost:8080/iwinmichl/api/config/date-config?format=text"

# 要点だけ
curl -s http://localhost:8080/iwinmichl/api/config/date-config \
  | jq '{status, verdict: .comparison.verdict, summary: .comparison.summary,
         file: .fileRead.sha256, resource: .resourceRead.sha256}'
```

## レスポンス

```jsonc
{
  "requestId": "…",
  "timestamp": "2026-09-01T12:34:56.789+09:00",
  "status": "SUCCESS",                    // SUCCESS / PARTIAL / FAILED
  "fileRead": {
    "readType": "FILE",
    "location": "/webapp/webapp9mf02/servlets/jp/iwin/base/tango/date_config.properties",
    "resolvedFilePath": "/webapp/…/date_config.properties",
    "exists": true, "readable": true, "loaded": true,
    "sizeBytes": 512,
    "lastModified": "2026-09-01T12:00:00+09:00",
    "sha256": "…",                        // 内容の指紋（変化の検知に使う）
    "charset": "UTF-8", "charsetMismatch": false,
    "propertyCount": 12,
    "properties": { "config.origin": "filesystem", … },
    "rawTextPreview": "…",                // コメント込みの生テキスト
    "elapsedMs": 3
  },
  "resourceRead": {
    "readType": "RESOURCE",
    "location": "classpath:jp/iwin/base/tango/date_config.properties",
    "resolvedUrl": "vfs:/content/dhapp.war/WEB-INF/classes/jp/iwin/base/tango/date_config.properties",
    "resolvedFilePath": "/opt/server/standalone/tmp/vfs/…/date_config.properties",
    "classLoader": "ModuleClassLoader for Module \"deployment.dhapp.war\"",
    "sha256": "…", "properties": { "config.origin": "war-archive", … }
  },
  "comparison": {
    "verdict": "DIFFERENT",               // IDENTICAL / SAME_PROPERTIES / DIFFERENT /
                                          // FILE_ONLY / RESOURCE_ONLY / BOTH_UNAVAILABLE
    "summary": "プロパティに差分がある。…",
    "sha256Match": false, "keySetMatch": false, "valuesMatch": false,
    "commonKeyCount": 5,
    "keysOnlyInFile": [], "keysOnlyInResource": ["date.calendar", …],
    "differentValues": { "config.origin": "file=filesystem / resource=war-archive" }
  },
  "deploymentOverlay": { … },             // 下記
  "report": "…",                          // ログ・コンソールへ出したテキストと同一
  "elapsedMs": 12
}
```

### `comparison.verdict`

| 値 | 意味 |
|---|---|
| `IDENTICAL` | バイト列レベルで一致（SHA-256 が同じ） |
| `SAME_PROPERTIES` | プロパティは全て一致するが、バイト列は違う（コメント・並び順・改行コードの差） |
| `DIFFERENT` | プロパティに差分がある（`keysOnlyIn*` / `differentValues` に内訳） |
| `FILE_ONLY` / `RESOURCE_ONLY` | 片方しか読めなかった |
| `BOTH_UNAVAILABLE` | どちらも読めなかった |

## deployment-overlay の反映検知

JBoss EAP の deployment-overlay は war を作り直さずに中のファイルを差し替えるため、
**リソース読み側だけ**が変化する。本 API は 2 つの方法で反映を検知する。

1. **管理モデル**（JMX ファサード `jboss.as:deployment-overlay=*`）を読み、
   overlay の定義・リンク先デプロイメント・`content-hash` を列挙する（＝設定として存在するか）
2. **実際に読めた内容の指紋**（解決先 URL・物理パス・SHA-256）を**前回の呼び出しと比較**する
   （＝実際に差し替わったか）

```jsonc
"deploymentOverlay": {
  "managementModelAvailable": true,
  "deploymentName": "dhapp.war",
  "overlayNames": ["date-config-overlay"],
  "overlays": [
    { "name": "date-config-overlay",
      "contentPaths": ["WEB-INF/classes/jp/iwin/base/tango/date_config.properties"],
      "contentAttributes": { "WEB-INF/…/date_config.properties.contentHash": "…" },
      "deployments": ["dhapp.war"],
      "appliesToThisDeployment": true,
      "overridesDateConfig": true }
  ],
  "dateConfigOverlayDefined": true,
  "overlayAppliedToThisDeployment": true,

  "previousSnapshotAvailable": true,
  "previousObservedAt": "2026-09-01T12:30:00+09:00",
  "previousResourceSha256": "…",
  "resourceContentChanged": true,          // ★差し替えが効いた証拠
  "resourceUrlChanged": false,
  "fileContentChanged": false,
  "detectionSummary": "管理モデル上、WEB-INF/classes/… を差し替える deployment-overlay が…"
}
```

### 確認手順

```
# 1. overlay 適用前に一度呼んで指紋を記録する
curl -s http://localhost:8080/iwinmichl/api/config/date-config | jq .resourceRead.sha256

# 2. 差し替え元ファイルを置く
mkdir -p /opt/overlay
cp samples/overlay/date_config.properties /opt/overlay/date_config.properties
chown jboss:jboss /opt/overlay/date_config.properties

# 3. overlay を適用（--redeploy-affected 付きなので dhapp.war が再デプロイされる）
$JBOSS_HOME/bin/jboss-cli.sh --connect --file=wildfly/configure-date-config-overlay.cli

# 4. もう一度呼ぶ → resourceContentChanged が true になる
curl -s http://localhost:8080/iwinmichl/api/config/date-config \
  | jq '{overlay: .deploymentOverlay.dateConfigOverlayDefined,
         changed: .deploymentOverlay.resourceContentChanged,
         origin:  .resourceRead.properties["config.origin"]}'
# → { "overlay": true, "changed": true, "origin": "deployment-overlay" }

# 5. 元へ戻す
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  --command="deployment-overlay remove --name=date-config-overlay \
             --deployments=dhapp.war --redeploy-affected"
```

指紋の比較は **JVM 内に保持した直近 1 回分**との比較なので、
`resourceContentChanged` を見るには overlay 適用の前後で API を呼ぶ必要がある
（`previousSnapshotAvailable=false` の回は初回で、比較対象が無い）。
overlay の**定義の有無**だけなら `dateConfigOverlayDefined` で 1 回の呼び出しでも分かる。

`managementModelAvailable=false`（JBoss EAP 以外での実行、jmx サブシステム無効など）でも
指紋の比較は動くため、検知機能そのものは失われない。

## ログ・コンソール出力

読み込み結果は次の 3 か所へ**同じ内容**で出力される。

| 出力先 | 内容 |
|---|---|
| ログ | `EFS_LOGS_PATH` 配下の各ログファイル（`application.log` ほか。`logback-spring.xml` の設定どおり） |
| コンソール | 標準出力へ直接（UTF-8 固定。ログ基盤の設定に依存しない） |
| レスポンス | `report` フィールド、または `?format=text` |

出力例:

```
================================================================================
date_config.properties 読み込み結果（ファイル読み vs リソース読み）
requestId=…, timestamp=…, status=SUCCESS, elapsedMs=12
================================================================================

[1] ファイル読み（war の外のファイルを直接読む）
  readType        : FILE
  location        : /webapp/webapp9mf02/servlets/jp/iwin/base/tango/date_config.properties
  …
  properties      : 12 件
      config.origin = filesystem
      …

[2] リソース読み（war 同梱・クラスパス配下）
  readType        : RESOURCE
  resolvedUrl     : vfs:/content/dhapp.war/WEB-INF/classes/jp/iwin/base/tango/date_config.properties
  …

[3] 比較結果（ファイル読み vs リソース読み）
  verdict         : DIFFERENT
  …

[4] deployment-overlay の検知
  overlayForPath  : true
  resourceChanged : true
  …
================================================================================
```

コンソール出力は `System.out` をそのまま使わず UTF-8 固定の `PrintStream` で書く。
コンテナのロケールが `POSIX` / `C` だと `stdout.encoding` が ASCII になり、
日本語のレポートが `?` に化けるため（Logback の CONSOLE アペンダも UTF-8 指定に揃えてある）。

## 設定

`src/main/resources/application.yml` の `app.config.date-config` 配下。すべて環境変数で上書きできる。

| 設定 | 環境変数 | 既定値 | 内容 |
|---|---|---|---|
| `file-path` | `DATE_CONFIG_FILE_PATH` | `/webapp/webapp9mf02/servlets/jp/iwin/base/tango/date_config.properties` | ファイル読みの対象 |
| `resource-name` | `DATE_CONFIG_RESOURCE_NAME` | `jp/iwin/base/tango/date_config.properties` | リソース読みの対象（先頭スラッシュ無し） |
| `charset` | `DATE_CONFIG_CHARSET` | `UTF-8` | 復号に使う文字セット |
| `include-raw-text` | `DATE_CONFIG_INCLUDE_RAW_TEXT` | `true` | 生テキストをレスポンス・ログに載せるか |

`charset` で復号できない byte 列があった場合は `charsetMismatch=true` を立てたうえで
読み進める（読み取り自体は止めない）。

## 実装

| クラス | 役割 |
|---|---|
| `controller/DateConfigController` | エンドポイント。`format=text` の切り替え |
| `service/DateConfigService` | 2 経路の読み込み・比較・指紋の保持・レポート生成 |
| `service/DeploymentOverlayInspector` | JMX 管理モデルから deployment-overlay の定義を読む |
| `service/ConsoleWriter` | コンソールへの UTF-8 固定出力 |
| `dto/DateConfigResponse` ほか | レスポンス |

`src/test/java/com/example/dhapp/service/DateConfigServiceTest` で、両経路の読み込み・
比較判定・内容変化の検知・ファイル欠損時の扱いを確認している。
