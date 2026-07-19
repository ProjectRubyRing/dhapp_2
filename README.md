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

## API 一覧

| API | パス | 内容 |
|---|---|---|
| 統合 | `POST /api/demo/execute` | Valkey 保存 → DB 2PC → 外部 API を一括実行 |
| DB のみ | `POST /api/db/execute` | DHCOMAP/DHINFAP への 2PC INSERT のみ（`failMode` でロールバック検証可） |
| ElastiCache のみ | `POST /api/cache/execute` | Valkey へ保存し、読み戻した内容を返す |
| 外部 API のみ | `POST /api/external/execute` | 設定 URL へ HTTP POST し結果を返す |

リクエストボディは全 API 共通（`sessionId`, `userId` は必須。`message` は任意。`failMode` は DB のみ有効）。

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

## ログ出力

各機能（demo / db / cache / external）は、REST API で処理した内容（リクエスト内容・処理ステップ・
レスポンス・処理時間）を詳細にログへ出力する。出力先・フォーマットは
`src/main/resources/logback-spring.xml` で構成し、出力ルートは **環境変数 `LOG_OUT_DIR`** で指定する。

> WildFly(JBoss EAP) デプロイ時は `jboss-deployment-structure.xml` で logging サブシステムを除外して
> いるため、ログ出力は war 内の Logback（Spring Boot 標準）が担う。

| ファイル | パス | 内容 |
|---|---|---|
| アプリログ | `${LOG_OUT_DIR}/application.log` | 各 REST API 機能の処理内容を DEBUG まで詳細に記録 |
| エラーログ | `${LOG_OUT_DIR}/error.log` | ERROR のみ。Java 例外スタックトレース形式（CloudWatch マルチライン検証用） |
| サーバログ | `${LOG_OUT_DIR}/mid/server.log` | JBoss EAP のサーバログ相当（EAP 既定フォーマット・フレームワーク含む全体） |

各 REST API（demo / db / cache / external）の処理内容は、上記に加えて以下のファイルにも
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
| gc.log | `${LOG_OUT_DIR}/mid/gc.log` |

さらに、環境変数 **`DATA_OUTPUT_DIR`** で指定したデータ出力ディレクトリにも、REST アプリのログを
合わせて `dummy.pdf` という名前で出力する（内容は他ファイルと同じ処理内容。未設定時は `./data`）。

| ファイル | パス |
|---|---|
| dummy.pdf | `${DATA_OUTPUT_DIR}/dummy.pdf` |

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
