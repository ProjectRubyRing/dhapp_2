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
