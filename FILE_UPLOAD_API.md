# ファイルアップロード API

curl で POST したファイルを **AP サーバ（WildFly/JBoss EAP）上に設定されているファイルアップロード先の
テンポラリフォルダ** に保存し、**保存場所と保存したファイルのサイズをログに出力する** REST API。

保存先の絶対パス・保存サイズ・適用中の上限値はレスポンス JSON にも含めて返すため、
クライアント側だけで「どこに」「何バイトで」保存されたかを確認できる。

- 実装: `com.example.dhapp.controller.FileUploadController` / `com.example.dhapp.service.FileUploadService`
- エラー整形: `com.example.dhapp.exception.GlobalExceptionHandler`

---

## 1. エンドポイント

| メソッド | パス | 内容 |
|---|---|---|
| `POST` | `/api/file/upload` | multipart/form-data でファイルを受け取り、テンポラリフォルダへ保存する |
| `GET`  | `/api/file/upload-info` | ファイルを送らずに、保存先テンポラリフォルダと適用中の上限を確認する |

コンテキストパスは `/iwinmichl` なので、実際の URL は次のようになる。

```
http://localhost:8080/iwinmichl/api/file/upload
http://localhost:8080/iwinmichl/api/file/upload-info
```

### リクエスト仕様（POST /api/file/upload）

| 項目 | 値 |
|---|---|
| Content-Type | `multipart/form-data`（curl の `-F` により boundary 付きで自動設定される） |
| パート `file` | **必須**。保存対象のファイル本体 |
| パート `note` | 任意。レスポンスにそのまま返るメモ文字列 |

---

## 2. 保存先テンポラリフォルダ

保存先は次の順序で決定する。決定経路はレスポンスの `tempDirSource` とログに出力されるため、
環境ごとの実際の保存先を必ず追跡できる。

| 優先度 | 保存先 | `tempDirSource` の値 |
|---|---|---|
| 1 | `spring.servlet.multipart.location`（設定されている場合） | `spring.servlet.multipart.location` |
| 2 | **AP サーバがこのデプロイに割り当てたテンポラリフォルダ**<br>（ServletContext 属性 `jakarta.servlet.context.tempdir`） | `servletContext:jakarta.servlet.context.tempdir` |
| 3 | システムプロパティ `java.io.tmpdir` | `system:java.io.tmpdir` |

**既定は 2 番**。WildFly/JBoss EAP ではデプロイごとにサーバのテンポラリ領域が割り当てられ、
通常 `$JBOSS_HOME/standalone/tmp/` 配下（例: `/opt/jboss/wildfly/standalone/tmp/dhapp.war`）になる。
実際の値は `GET /api/file/upload-info` で確認できる。

保存ファイル名は衝突を避けるため次の形式に加工する。

```
upload_<yyyyMMddHHmmssSSS>_<UUID 先頭 8 桁>_<元ファイル名>
例) upload_20260804122145123_a1b2c3d4_sample.bin
```

元ファイル名はディレクトリ区切り文字・Windows 予約文字・制御文字を `_` に置換したうえで使う
（パストラバーサル対策。保存直前に保存先ディレクトリ配下であることも検証する）。
加工前の名前はレスポンスの `originalFilename` で確認できる。

> **保存したファイルは自動削除しない。** テンポラリ領域に置かれるため、AP サーバの再起動や
> 再デプロイ、OS のテンポラリ掃除で消える可能性がある。恒久保管が必要な場合は
> `MULTIPART_LOCATION` で永続ディレクトリを指定すること。

---

## 3. 設定

### アプリ側（`src/main/resources/application.yml`）

```yaml
spring:
  servlet:
    multipart:
      enabled: true
      max-file-size: ${MULTIPART_MAX_FILE_SIZE:5MB}
      max-request-size: ${MULTIPART_MAX_REQUEST_SIZE:5MB}
      file-size-threshold: ${MULTIPART_FILE_SIZE_THRESHOLD:0B}
      location: ${MULTIPART_LOCATION:}
```

| 環境変数 | 既定値 | 内容 |
|---|---|---|
| `MULTIPART_MAX_FILE_SIZE` | `5MB` | 1 ファイルあたりの上限 |
| `MULTIPART_MAX_REQUEST_SIZE` | `5MB` | マルチパートリクエスト全体の上限 |
| `MULTIPART_FILE_SIZE_THRESHOLD` | `0B` | この値を超えた分をテンポラリファイルへ退避する閾値（0 = 常にディスク） |
| `MULTIPART_LOCATION` | （空） | 保存先の明示指定。空なら AP サーバ既定のテンポラリフォルダ |

```
export MULTIPART_MAX_FILE_SIZE=20MB
export MULTIPART_MAX_REQUEST_SIZE=20MB
export MULTIPART_LOCATION=/var/tmp/dhapp-upload
```

### AP サーバ側（WildFly/Undertow の `max-post-size`）

Undertow の http-listener には、**アプリの設定とは独立した**リクエストボディ全体の上限
`max-post-size` がある（既定 `10485760` バイト = 10MB）。

```
# 現在値の確認
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  '/subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size)'

# 変更（例: 20MB）
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  '/subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=max-post-size,value=20971520)'
$JBOSS_HOME/bin/jboss-cli.sh --connect ':reload'
```

`standalone.xml` を直接編集する場合:

```xml
<http-listener name="default" socket-binding="http" max-post-size="20971520"/>
```

> **推奨:** アプリ側の `max-request-size` は AP サーバ側の `max-post-size` **より小さく**設定する。
> そうすると上限超過が必ずアプリ側で検知され、後述の**詳細な 413 JSON レスポンス**を返せる。
> 既定値（アプリ 5MB < コンテナ 10MB）はこの関係になっている。詳細は「6. サイズ上限超過時の挙動」を参照。

---

## 4. 利用方法

### ビルドとデプロイ

```
mvn -DskipTests clean package     # → target/dhapp.war
```

生成した war を WildFly にデプロイする（既存の手順どおり）。

### 事前確認: 保存先と上限を調べる

```
curl -i http://localhost:8080/iwinmichl/api/file/upload-info
```

```json
{
  "status": "OK",
  "tempDirectory": "/opt/jboss/wildfly/standalone/tmp/dhapp.war",
  "tempDirSource": "servletContext:jakarta.servlet.context.tempdir",
  "tempDirectoryExists": true,
  "tempDirectoryWritable": true,
  "limits": {
    "maxFileSize": "5.00 MB",
    "maxFileSizeBytes": 5242880,
    "maxRequestSize": "5.00 MB",
    "maxRequestSizeBytes": 5242880,
    "fileSizeThreshold": "0 B",
    "fileSizeThresholdBytes": 0,
    "multipartLocation": "",
    "containerMaxPostSizeNote": "AP サーバ側の上限はアプリからは参照できない。WildFly/JBoss EAP では /subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size) で確認する（既定 10485760 バイト = 10MB）。"
  }
}
```

### テスト用ファイルの作成

```
# Linux
dd if=/dev/urandom of=/tmp/sample_1mb.bin bs=1M count=1
dd if=/dev/urandom of=/tmp/sample_8mb.bin bs=1M count=8

# Windows (PowerShell)
fsutil file createnew C:\temp\sample_1mb.bin 1048576
fsutil file createnew C:\temp\sample_8mb.bin 8388608
```

### アップロード

```
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin" \
  -F "note=upload test"
```

---

## 5. curl でのアップロードファイル指定方法

ファイルは **`-F` / `--form` の `@` 記法**で指定する。パート名は **`file`** 固定。

```
-F "file=@<ファイルパス>"
```

### 5-1. 基本パターン

| 目的 | 指定方法 |
|---|---|
| 絶対パス | `-F "file=@/tmp/sample_1mb.bin"` |
| 相対パス（カレントディレクトリ基準） | `-F "file=@sample_1mb.bin"` |
| Windows のパス | `-F "file=@C:/temp/sample_1mb.bin"` |
| パスに空白を含む | `-F "file=@/tmp/my sample.bin"`（**必ず全体をクォートする**） |
| Content-Type を明示する | `-F "file=@/tmp/report.pdf;type=application/pdf"` |
| サーバへ通知するファイル名を変える | `-F "file=@/tmp/sample.bin;filename=renamed.bin"` |
| 両方を指定する | `-F "file=@/tmp/sample.bin;type=application/pdf;filename=renamed.bin"` |
| メモを一緒に送る | `-F "file=@/tmp/sample.bin" -F "note=upload test"` |
| `@` や `;` を含むファイル名を送る | `--form "file=@\"/tmp/odd@name;1.bin\""` |
| 標準入力から送る | `cat sample.bin \| curl -F "file=@-;filename=stdin.bin" ...` |

`;type=` を省略した場合、curl は拡張子から推測するか `application/octet-stream` を送る。
サーバはこの値を検証せず、`contentType` としてそのまま記録・返却する。

### 5-2. 実行例

```
# 最小
curl -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin"

# レスポンスヘッダも表示（推奨）
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin" -F "note=upload test"

# JSON を整形して表示
curl -s -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin" | jq .

# 保存先とサイズだけ取り出す
curl -s -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_1mb.bin" | jq '{storedPath, sizeBytes, sizeReadable}'

# 送受信の全ヘッダを見る（上限超過の調査時に有用）
curl -v -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_8mb.bin"
```

Windows の `cmd.exe` では行継続が `^`、クォートは `"` になる。

```
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload ^
  -F "file=@C:/temp/sample_1mb.bin" ^
  -F "note=upload test"
```

PowerShell では `curl` が `Invoke-WebRequest` のエイリアスになっている場合があるため、
**`curl.exe` と明示する**こと。

```powershell
curl.exe -i -X POST http://localhost:8080/iwinmichl/api/file/upload `
  -F "file=@C:/temp/sample_1mb.bin" `
  -F "note=upload test"
```

### 5-3. よくある間違い

| 誤り | 何が起きるか | 正しい書き方 |
|---|---|---|
| `-d @file` / `--data-binary @file` | ボディが `multipart/form-data` にならず 415 になる | `-F "file=@file"` |
| `-F "file=<sample.bin"` | `<` はファイル内容を**通常フィールドの値**として送る書式。ファイルパートにならず `MISSING_FILE_PART`(400) | `-F "file=@sample.bin"` |
| `-H "Content-Type: multipart/form-data"` を手で付ける | boundary が欠落し `MULTIPART_PARSE_ERROR`(400) | ヘッダは指定しない（curl が自動設定） |
| `-F "upload=@sample.bin"` | パート名が違うため `MISSING_FILE_PART`(400) | パート名は `file` |
| `-F file=@my sample.bin`（クォート無し） | 空白で引数が切れてパス不正 | `-F "file=@my sample.bin"` |
| `-T sample.bin`（PUT アップロード） | multipart ではなく生ボディの PUT になる | `-F "file=@sample.bin"` |
| 存在しないパス | curl 側で `Failed to open/read local data` となり送信されない | パスを確認 |

### 5-4. `Expect: 100-continue` について

curl は 1KB を超えるボディを送る際、自動的に `Expect: 100-continue` を付ける。
そのため `curl -i` の出力では、本レスポンスの前に `HTTP/1.1 100 Continue` が表示される。

```
HTTP/1.1 100 Continue

HTTP/1.1 200 OK
...
```

無効化する場合は `-H "Expect:"` を付ける。上限超過の調査で「サーバがどの時点で応答したか」を
切り分けたいときに使う。

```
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -H "Expect:" -F "file=@/tmp/sample_8mb.bin"
```

---

## 6. 詳細なレスポンス情報（正常系）

### 6-1. curl の出力

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/sample_1mb.bin" \
    -F "note=upload test"

HTTP/1.1 100 Continue

HTTP/1.1 200 OK
Connection: keep-alive
Content-Type: application/json
Content-Length: 1174
Date: Tue, 04 Aug 2026 03:21:45 GMT

{
  "status": "SUCCESS",
  "requestId": "3f2b1c88-9d41-4a6e-9c0b-71a2f0d5e123",
  "receivedAt": "2026-08-04T12:21:45.123+09:00",
  "formFieldName": "file",
  "originalFilename": "sample_1mb.bin",
  "contentType": "application/octet-stream",
  "sizeBytes": 1048576,
  "sizeReadable": "1.00 MB",
  "sha256": "30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58",
  "storedFileName": "upload_20260804122145123_a1b2c3d4_sample_1mb.bin",
  "storedPath": "/opt/jboss/wildfly/standalone/tmp/dhapp.war/upload_20260804122145123_a1b2c3d4_sample_1mb.bin",
  "storedDirectory": "/opt/jboss/wildfly/standalone/tmp/dhapp.war",
  "tempDirSource": "servletContext:jakarta.servlet.context.tempdir",
  "note": "upload test",
  "elapsedMs": 12,
  "limits": {
    "maxFileSize": "5.00 MB",
    "maxFileSizeBytes": 5242880,
    "maxRequestSize": "5.00 MB",
    "maxRequestSizeBytes": 5242880,
    "fileSizeThreshold": "0 B",
    "fileSizeThresholdBytes": 0,
    "multipartLocation": "",
    "containerMaxPostSizeNote": "AP サーバ側の上限はアプリからは参照できない。WildFly/JBoss EAP では /subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size) で確認する（既定 10485760 バイト = 10MB）。"
  }
}
```

> 実際のレスポンスは 1 行の JSON。上記は読みやすさのために整形している（`| jq .` で同じ表示になる）。
> JSON のフィールド順は環境により前後することがある。

### 6-2. レスポンスフィールド

| フィールド | 型 | 内容 |
|---|---|---|
| `status` | string | 正常時は `SUCCESS` |
| `requestId` | string | リクエスト識別子。ログの `requestId=` と突き合わせる |
| `receivedAt` | string | リクエスト受信時刻（ISO 8601 / オフセット付き） |
| `formFieldName` | string | multipart のパート名（常に `file`） |
| `originalFilename` | string | クライアントが送信した元のファイル名 |
| `contentType` | string | クライアントが申告した Content-Type |
| **`sizeBytes`** | number | **保存したファイルのサイズ（バイト）** |
| `sizeReadable` | string | 同上を人が読める単位に整形した値 |
| `sha256` | string | 保存したファイルの SHA-256。送信内容と一致するかの検証に使う |
| `storedFileName` | string | 実際に保存したファイル名 |
| **`storedPath`** | string | **保存したファイルの絶対パス** |
| `storedDirectory` | string | 保存先ディレクトリ（＝AP サーバのテンポラリフォルダ）の絶対パス |
| `tempDirSource` | string | 保存先をどの設定から解決したか（「2. 保存先テンポラリフォルダ」参照） |
| `note` | string | `-F "note=..."` で送った値。未指定なら `null` |
| `elapsedMs` | number | サーバ側の処理時間（ミリ秒） |
| `limits` | object | 適用中のアップロード上限（下表） |

`limits` オブジェクト:

| フィールド | 型 | 内容 |
|---|---|---|
| `maxFileSize` / `maxFileSizeBytes` | string / number | 1 ファイルあたりの上限 |
| `maxRequestSize` / `maxRequestSizeBytes` | string / number | マルチパートリクエスト全体の上限 |
| `fileSizeThreshold` / `fileSizeThresholdBytes` | string / number | ディスク退避の閾値 |
| `multipartLocation` | string | `spring.servlet.multipart.location` の設定値（空なら AP サーバ既定） |
| `containerMaxPostSizeNote` | string | AP サーバ側 `max-post-size` の確認方法（アプリからは値を取得できないため案内文） |

### 6-3. 検証

```
# 保存先に実ファイルがあること・サイズが一致すること
ls -l /opt/jboss/wildfly/standalone/tmp/dhapp.war/upload_20260804122145123_a1b2c3d4_sample_1mb.bin

# 内容が一致すること（レスポンスの sha256 と比較）
sha256sum /tmp/sample_1mb.bin
```

### 6-4. ログ出力

`${LOG_OUT_DIR}/application.log`（および `keax0003.log` ほか、他の REST API と同じ全ファイル）に
次の内容が出力される。**保存場所と保存したファイルのサイズは INFO で出力される。**

```
2026-08-04 12:21:45.100 INFO  [com.example.dhapp.controller.FileUploadController] POST /api/file/upload received. requestId=3f2b1c88-9d41-4a6e-9c0b-71a2f0d5e123, originalFilename=sample_1mb.bin, declaredSizeBytes=1048576, contentType=application/octet-stream, contentLength=1048837
2026-08-04 12:21:45.104 DEBUG [com.example.dhapp.service.FileUploadService] File upload starting. requestId=3f2b1c88-..., fieldName=file, originalFilename=sample_1mb.bin, declaredSizeBytes=1048576, contentType=application/octet-stream, tempDir=/opt/jboss/wildfly/standalone/tmp/dhapp.war, tempDirSource=servletContext:jakarta.servlet.context.tempdir, storedFileName=upload_20260804122145123_a1b2c3d4_sample_1mb.bin
2026-08-04 12:21:45.112 INFO  [com.example.dhapp.service.FileUploadService] File uploaded and stored. requestId=3f2b1c88-..., storedPath=/opt/jboss/wildfly/standalone/tmp/dhapp.war/upload_20260804122145123_a1b2c3d4_sample_1mb.bin, sizeBytes=1048576, sizeReadable=1.00 MB
2026-08-04 12:21:45.112 DEBUG [com.example.dhapp.service.FileUploadService] File upload detail. requestId=3f2b1c88-..., fieldName=file, originalFilename=sample_1mb.bin, contentType=application/octet-stream, storedDirectory=/opt/jboss/wildfly/standalone/tmp/dhapp.war, tempDirSource=servletContext:jakarta.servlet.context.tempdir, storedFileName=upload_20260804122145123_a1b2c3d4_sample_1mb.bin, declaredSizeBytes=1048576, writtenBytes=1048576, sha256=30e14955..., 
2026-08-04 12:21:45.113 INFO  [com.example.dhapp.controller.FileUploadController] POST /api/file/upload done. requestId=3f2b1c88-..., status=SUCCESS, storedPath=/opt/jboss/wildfly/standalone/tmp/dhapp.war/upload_20260804122145123_a1b2c3d4_sample_1mb.bin, sizeBytes=1048576, sizeReadable=1.00 MB, tempDirSource=servletContext:jakarta.servlet.context.tempdir, elapsedMs=12
```

`contentLength`（リクエスト全体）は multipart の boundary やヘッダを含むため、
ファイル本体の `sizeBytes` よりわずかに大きくなる。

---

## 7. サイズ上限超過時の詳細なレスポンス

サイズ上限は**アプリ側**と**AP サーバ側**の 2 段ある。どちらで弾かれたかは
レスポンスの **`limitSource`** で判別できる。

| # | 上限 | 設定箇所 | `errorCode` | `limitSource` |
|---|---|---|---|---|
| 1 | アプリ側 | `spring.servlet.multipart.max-file-size` / `max-request-size` | `MAX_UPLOAD_SIZE_EXCEEDED` | `application(spring.servlet.multipart.*)` |
| 2 | AP サーバ側 | Undertow http-listener の `max-post-size` | `MAX_POST_SIZE_EXCEEDED` | `container(undertow max-post-size)` |

どちらも HTTP ステータスは **413 Request Entity Too Large**。

> **どちらが先に効くか**
> `max-request-size` の判定はリクエストの `Content-Length` を見て**ボディを読む前**に行われる
> （curl の `-F` は必ず `Content-Length` を付ける）。一方 `max-post-size` はボディを読みながら
> 判定される。したがって既定設定（アプリ 5MB < コンテナ 10MB）では**常にアプリ側が先に検知**し、
> 下記 7-1 の詳細な JSON が返る。7-2 の AP サーバ側の挙動を再現するには、
> アプリ側の上限を `max-post-size` より大きくする必要がある（手順は 7-3）。

### 7-1. アプリ側の上限に引っかかった場合

上限 5MB に対して 8MB のファイルを送る。

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/sample_8mb.bin"

HTTP/1.1 100 Continue

HTTP/1.1 413 Request Entity Too Large
Connection: keep-alive
Content-Type: application/json
Content-Length: 1442
Date: Tue, 04 Aug 2026 03:25:10 GMT

{
  "status": "ERROR",
  "errorCode": "MAX_UPLOAD_SIZE_EXCEEDED",
  "httpStatus": 413,
  "message": "アップロードサイズが上限を超えました。許容される最大サイズは 5.00 MB (5242880 バイト) です。",
  "requestId": "b71c0a2e-5f88-4c31-a0d9-2e6b8c4415ff",
  "timestamp": "2026-08-04T12:25:10.442+09:00",
  "requestContentLength": 8388869,
  "requestContentLengthReadable": "8.00 MB",
  "permittedMaxBytes": 5242880,
  "permittedMaxReadable": "5.00 MB",
  "limitSource": "application(spring.servlet.multipart.*)",
  "exceptionClass": "org.springframework.web.multipart.MaxUploadSizeExceededException",
  "exceptionMessage": "Maximum upload size exceeded",
  "rootCauseClass": "java.lang.IllegalStateException",
  "rootCauseMessage": "UT000067: Request entity was too large, max size is 5242880",
  "hint": "アプリ側の上限は spring.servlet.multipart.max-file-size / max-request-size （環境変数 MULTIPART_MAX_FILE_SIZE / MULTIPART_MAX_REQUEST_SIZE）で変更する。AP サーバ側の max-post-size より小さく設定しておくと、この詳細な JSON レスポンスを返せる。",
  "limits": {
    "maxFileSize": "5.00 MB",
    "maxFileSizeBytes": 5242880,
    "maxRequestSize": "5.00 MB",
    "maxRequestSizeBytes": 5242880,
    "fileSizeThreshold": "0 B",
    "fileSizeThresholdBytes": 0,
    "multipartLocation": "",
    "containerMaxPostSizeNote": "AP サーバ側の上限はアプリからは参照できない。WildFly/JBoss EAP では /subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size) で確認する（既定 10485760 バイト = 10MB）。"
  }
}
```

> `rootCauseClass` / `rootCauseMessage` は AP サーバの実装とバージョンに依存する
> （WildFly では Undertow の `UT0000xx` 形式のメッセージが入る）。
> **判定は `errorCode` と `limitSource` で行うこと。** 上限値の表示は、例外から上限バイト数を
> 取得できない場合、設定値（`max-request-size`）にフォールバックする。

ログ（`application.log` / `error.log` の両方に出力される）:

```
2026-08-04 12:25:10.442 ERROR [com.example.dhapp.exception.GlobalExceptionHandler] File upload rejected: size limit exceeded. requestId=b71c0a2e-..., limitSource=application(spring.servlet.multipart.*), contentLength=8388869, permittedMaxBytes=5242880, rootCause=Maximum upload size exceeded | UT000067: Request entity was too large, max size is 5242880
org.springframework.web.multipart.MaxUploadSizeExceededException: Maximum upload size exceeded
	at org.springframework.web.multipart.support.StandardMultipartHttpServletRequest.handleParseFailure(StandardMultipartHttpServletRequest.java:130)
	...
Caused by: java.lang.IllegalStateException: UT000067: Request entity was too large, max size is 5242880
	... 42 more
```

`error.log` にはスタックトレース形式で記録されるため、CloudWatch のマルチライン設定でも
1 イベントとして扱える（README の error.log の項を参照）。

### 7-2. AP サーバ側の `max-post-size` に引っかかった場合

Undertow は `max-post-size` を超えたリクエストを検知すると、**ボディを読み切らずに接続を打ち切る**。
アプリまで例外が到達した場合は次の 413 JSON を返す。

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/sample_12mb.bin"

HTTP/1.1 413 Request Entity Too Large
Connection: close
Content-Type: application/json
Content-Length: 1489
Date: Tue, 04 Aug 2026 03:31:02 GMT

{
  "status": "ERROR",
  "errorCode": "MAX_POST_SIZE_EXCEEDED",
  "httpStatus": 413,
  "message": "AP サーバ側のリクエストサイズ上限 (max-post-size) を超えたため、リクエストボディの読み取りが中断されました。上限は 10.00 MB (10485760 バイト) です。",
  "requestId": "c93d5f10-2a77-4be1-8f52-90a41d6c7788",
  "timestamp": "2026-08-04T12:31:02.771+09:00",
  "requestContentLength": 12583173,
  "requestContentLengthReadable": "12.00 MB",
  "permittedMaxBytes": 10485760,
  "permittedMaxReadable": "10.00 MB",
  "limitSource": "container(undertow max-post-size)",
  "exceptionClass": "org.springframework.web.multipart.MultipartException",
  "exceptionMessage": "Failed to parse multipart servlet request",
  "rootCauseClass": "java.io.IOException",
  "rootCauseMessage": "UT000020: Connection terminated as request was larger than 10485760",
  "hint": "AP サーバ側の上限を引き上げる場合は WildFly の http-listener の max-post-size を変更する: /subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=max-post-size,value=<バイト数>) 実行後 :reload。",
  "limits": {
    "maxFileSize": "50.00 MB",
    "maxFileSizeBytes": 52428800,
    "maxRequestSize": "50.00 MB",
    "maxRequestSizeBytes": 52428800,
    "fileSizeThreshold": "0 B",
    "fileSizeThresholdBytes": 0,
    "multipartLocation": "",
    "containerMaxPostSizeNote": "AP サーバ側の上限はアプリからは参照できない。WildFly/JBoss EAP では /subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size) で確認する（既定 10485760 バイト = 10MB）。"
  }
}
```

ポイント:

- `permittedMaxBytes` には `UT000020` のメッセージから取り出した**実際の `max-post-size` の値**が入る。
  アプリからは設定値を直接参照できないため、この経路でのみ実値が得られる。
- `limits` に出るのは**アプリ側**の上限であり、この 413 の原因となった上限ではない。
  原因の上限は `permittedMaxBytes` を見ること。
- `Connection: close` が返る（Undertow がボディを読み切っていないため接続を再利用できない）。

#### 注意: curl 側で応答を受け取れないことがある

Undertow はボディの読み取り中に接続を打ち切るため、curl がまだ送信中だと**レスポンスを
受け取れずにエラー終了**する。この場合 JSON は表示されない。

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/sample_12mb.bin"
curl: (55) Send failure: Connection was reset
```

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/sample_12mb.bin"
curl: (56) Recv failure: Connection reset by peer
```

どちらの場合も、AP サーバ側には必ず記録が残る。`${LOG_OUT_DIR}/mid/server.log` を確認する。

```
2026-08-04 12:31:02,770 ERROR [io.undertow.request] (default task-1) UT005023: Exception handling request to /iwinmichl/api/file/upload: java.io.IOException: UT000020: Connection terminated as request was larger than 10485760
	at io.undertow.conduits.FixedLengthStreamSourceConduit.checkMaxSize(FixedLengthStreamSourceConduit.java:...)
	...
```

**この不確実性を避けるため、アプリ側の `max-request-size` を `max-post-size` より小さく
設定しておくことを推奨する**（7-1 の経路になり、必ず詳細な JSON が返る）。

### 7-3. AP サーバ側の上限を再現する手順

既定設定ではアプリ側（5MB）が先に効くため、AP サーバ側の 413 を再現するには
アプリ側の上限を `max-post-size`（既定 10MB）より大きくする。

```
# 1) アプリ側の上限を 50MB に上げてデプロイ／再起動
export MULTIPART_MAX_FILE_SIZE=50MB
export MULTIPART_MAX_REQUEST_SIZE=50MB

# 2) max-post-size の現在値を確認（既定 10485760）
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  '/subsystem=undertow/server=default-server/http-listener=default:read-attribute(name=max-post-size)'

# 3) max-post-size を超えるファイルを作って送る
dd if=/dev/urandom of=/tmp/sample_12mb.bin bs=1M count=12
curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
  -F "file=@/tmp/sample_12mb.bin"
```

小さい `max-post-size` で試すほうが速い（例: 1MB に下げて 2MB を送る）。

```
$JBOSS_HOME/bin/jboss-cli.sh --connect \
  '/subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=max-post-size,value=1048576)'
$JBOSS_HOME/bin/jboss-cli.sh --connect ':reload'
```

---

## 8. その他のエラーレスポンス

すべて `UploadErrorResponse` 形式（7 章と同じフィールド構成）。
サイズ超過以外では `permittedMaxBytes` は `-1`、`permittedMaxReadable` は `"unknown"`、
`limitSource` は `"unknown"` になる。

| 状況 | HTTP | `errorCode` |
|---|---|---|
| パート `file` が無い（パート名の間違い） | 400 | `MISSING_FILE_PART` |
| multipart ではないリクエスト / boundary 不正 | 400 | `MULTIPART_PARSE_ERROR` |
| 0 バイトのファイル | 400 | `EMPTY_FILE` |
| ファイル名が保存先の外を指す | 400 | `INVALID_FILE_NAME` |
| テンポラリフォルダへの書き込み失敗（権限・容量不足など） | 500 | `IO_ERROR` |

### 例: パート名を間違えた場合

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "upload=@/tmp/sample_1mb.bin"

HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "status": "ERROR",
  "errorCode": "MISSING_FILE_PART",
  "httpStatus": 400,
  "message": "必須のパート \"file\" がリクエストに含まれていません。",
  "requestId": "5a10c7e2-...",
  "timestamp": "2026-08-04T12:40:11.005+09:00",
  "requestContentLength": 1048841,
  "requestContentLengthReadable": "1.00 MB",
  "permittedMaxBytes": -1,
  "permittedMaxReadable": "unknown",
  "limitSource": "unknown",
  "exceptionClass": "org.springframework.web.multipart.support.MissingServletRequestPartException",
  "exceptionMessage": "Required part 'file' is not present.",
  "rootCauseClass": "org.springframework.web.multipart.support.MissingServletRequestPartException",
  "rootCauseMessage": "Required part 'file' is not present.",
  "hint": "curl で -F \"file=@<ファイルパス>\" を指定する。パート名が \"file\" 以外だとこのエラーになる。",
  "limits": { "...": "..." }
}
```

### 例: 空ファイルを送った場合

```
$ : > /tmp/empty.bin
$ curl -s -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -F "file=@/tmp/empty.bin" | jq '{errorCode, message}'

{
  "errorCode": "EMPTY_FILE",
  "message": "アップロードされたファイルが空です。curl の -F \"file=@<ファイルパス>\" で存在するファイルを指定してください。"
}
```

### 例: multipart 以外で送った場合

```
$ curl -i -X POST http://localhost:8080/iwinmichl/api/file/upload \
    -H "Content-Type: application/json" -d '{"a":1}'

HTTP/1.1 415 Unsupported Media Type
```

`consumes = multipart/form-data` を満たさないため、コントローラに到達する前に
Spring MVC が 415 を返す（`UploadErrorResponse` 形式ではない）。

---

## 9. 参考

- 全 API の一覧・ログ出力先: [README.md](README.md)
- ログファイル一覧は `src/main/resources/logback-spring.xml` で構成。出力ルートは環境変数 `LOG_OUT_DIR`
