#!/usr/bin/env bash
# =====================================================================
# JVM / JBoss EAP のトラストストアに登録した自己署名証明書 (cacert.crt) を使って、
# Java アプリではなく curl で HTTPS 接続できることを確認するスクリプト。
#
# curl は JKS / PKCS12 のトラストストアを直接は読めないため、
# keytool でトラストストアから PEM バンドルを書き出し、それを --cacert に渡す。
#
#   使い方:
#     ./scripts/verify-truststore-curl.sh -u https://example.internal:8443/health
#     ./scripts/verify-truststore-curl.sh -u https://... -t /opt/jboss/certs/truststore.jks -p secret
#     ./scripts/verify-truststore-curl.sh -u https://... -c /opt/jboss/certs/cacert.crt
#
#   トラストストアのパス・パスワードを省略した場合は、起動中の JBoss EAP プロセスの
#   -Djavax.net.ssl.trustStore / -Djavax.net.ssl.trustStorePassword から自動検出する。
#
#   終了コード: 0 = 全テスト成功 / 1 = 失敗あり
#
# 詳細と REST API の利用方法は TLS_SELFSIGNED_API.md を参照。
# =====================================================================
set -uo pipefail

TARGET_URL="${TARGET_URL:-}"
TRUSTSTORE_PATH="${TRUSTSTORE_PATH:-}"
TRUSTSTORE_PASSWORD="${TRUSTSTORE_PASSWORD:-}"
CACERT_FILE="${CACERT_FILE:-}"
KEYTOOL_BIN="${KEYTOOL_BIN:-}"
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-10}"
MAX_TIME="${MAX_TIME:-30}"

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0
SKIP_COUNT=0

WORK_DIR=""

usage() {
    cat <<'USAGE'
使い方: verify-truststore-curl.sh -u <https URL> [オプション]

  -u, --url <URL>          接続先 (https)。必須。環境変数 TARGET_URL でも指定可
  -t, --truststore <PATH>  JVM トラストストア。省略時は JBoss プロセスから自動検出
  -p, --password <PASS>    トラストストアのパスワード。省略時は同上
  -c, --cacert <PATH>      自己署名証明書 cacert.crt。指定するとストア内との一致も検証する
  -k, --keytool <PATH>     keytool のパス。省略時は PATH / JAVA_HOME から探す
      --connect-timeout N  curl の接続タイムアウト秒 (既定 10)
      --max-time N         curl の最大実行秒 (既定 30)
  -h, --help               このヘルプ

例:
  ./verify-truststore-curl.sh -u https://example.internal:8443/health \
      -t /opt/jboss/certs/truststore.jks -p secret -c /opt/jboss/certs/cacert.crt
USAGE
}

# --- 出力ヘルパ -------------------------------------------------------
if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_CYAN=$'\033[36m'; C_OFF=$'\033[0m'
else
    C_RED=""; C_GREEN=""; C_YELLOW=""; C_CYAN=""; C_OFF=""
fi

section() { printf '\n%s=== %s ===%s\n' "$C_CYAN" "$1" "$C_OFF"; }
info()    { printf '     %s\n' "$1"; }
pass()    { PASS_COUNT=$((PASS_COUNT + 1)); printf '%s[PASS]%s %s\n' "$C_GREEN"  "$C_OFF" "$1"; }
fail()    { FAIL_COUNT=$((FAIL_COUNT + 1)); printf '%s[FAIL]%s %s\n' "$C_RED"    "$C_OFF" "$1"; }
warn()    { WARN_COUNT=$((WARN_COUNT + 1)); printf '%s[WARN]%s %s\n' "$C_YELLOW" "$C_OFF" "$1"; }
skip()    { SKIP_COUNT=$((SKIP_COUNT + 1)); printf '[SKIP] %s\n' "$1"; }

cleanup() {
    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

# --- 引数 -------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        -u|--url)             TARGET_URL="${2:-}"; shift 2 ;;
        -t|--truststore)      TRUSTSTORE_PATH="${2:-}"; shift 2 ;;
        -p|--password)        TRUSTSTORE_PASSWORD="${2:-}"; shift 2 ;;
        -c|--cacert)          CACERT_FILE="${2:-}"; shift 2 ;;
        -k|--keytool)         KEYTOOL_BIN="${2:-}"; shift 2 ;;
        --connect-timeout)    CONNECT_TIMEOUT="${2:-}"; shift 2 ;;
        --max-time)           MAX_TIME="${2:-}"; shift 2 ;;
        -h|--help)            usage; exit 0 ;;
        *) printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "$TARGET_URL" ]; then
    printf '接続先 URL が未指定。-u https://... を指定する。\n\n' >&2
    usage >&2
    exit 2
fi
case "$TARGET_URL" in
    https://*) ;;
    *) printf 'TLS の確認が目的のため https の URL を指定する: %s\n' "$TARGET_URL" >&2; exit 2 ;;
esac

command -v curl >/dev/null 2>&1 || { printf 'curl が見つからない。\n' >&2; exit 2; }

# --- JBoss EAP の起動パラメータからトラストストア情報を自動検出 -------
detect_from_jboss_process() {
    # standalone.sh は最終的に jboss-modules.jar で org.jboss.as.standalone を起動する。
    ps -ww -eo args= 2>/dev/null \
        | grep -E 'org\.jboss\.as\.standalone|jboss-modules\.jar' \
        | grep -v grep \
        | head -n 1
}

extract_sysprop() {
    # $1: コマンドライン全体, $2: システムプロパティ名
    printf '%s\n' "$1" | tr ' ' '\n' | grep -m1 -- "-D${2}=" | sed "s/^-D${2}=//"
}

section "0. トラストストアの特定"
JBOSS_CMDLINE="$(detect_from_jboss_process)"
if [ -n "$JBOSS_CMDLINE" ]; then
    info "起動中の JBoss EAP プロセスを検出した。"
    if [ -z "$TRUSTSTORE_PATH" ]; then
        TRUSTSTORE_PATH="$(extract_sysprop "$JBOSS_CMDLINE" javax.net.ssl.trustStore)"
        [ -n "$TRUSTSTORE_PATH" ] && info "-Djavax.net.ssl.trustStore を検出: $TRUSTSTORE_PATH"
    fi
    if [ -z "$TRUSTSTORE_PASSWORD" ]; then
        TRUSTSTORE_PASSWORD="$(extract_sysprop "$JBOSS_CMDLINE" javax.net.ssl.trustStorePassword)"
        [ -n "$TRUSTSTORE_PASSWORD" ] && info "-Djavax.net.ssl.trustStorePassword を検出（値は表示しない）"
    fi
else
    info "起動中の JBoss EAP プロセスは見つからなかった（引数指定で続行）。"
fi

if [ -z "$TRUSTSTORE_PATH" ]; then
    fail "トラストストアのパスが不明。-t /path/to/truststore.jks を指定する。"
    printf '\n%s\n' "検出できる起動パラメータの例: -Djavax.net.ssl.trustStore=/opt/jboss/certs/truststore.jks"
    exit 1
fi
info "トラストストア: $TRUSTSTORE_PATH"
info "接続先        : $TARGET_URL"
[ -n "$CACERT_FILE" ] && info "自己署名証明書: $CACERT_FILE"

# --- keytool の解決 ---------------------------------------------------
# JAVA_HOME を優先する。JBoss EAP を動かしている JVM の keytool を使うのが確実なため
# （PATH 上の keytool が古い Java だと、PKCS12 のトラストストアを読めないことがある）。
if [ -z "$KEYTOOL_BIN" ]; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
        KEYTOOL_BIN="$JAVA_HOME/bin/keytool"
    elif command -v keytool >/dev/null 2>&1; then
        KEYTOOL_BIN="$(command -v keytool)"
    fi
fi

WORK_DIR="$(mktemp -d)"
PEM_BUNDLE="$WORK_DIR/truststore-bundle.pem"

# =====================================================================
section "1. トラストストアファイル"
# =====================================================================
if [ -r "$TRUSTSTORE_PATH" ]; then
    pass "トラストストアを読み取れる: $TRUSTSTORE_PATH"
else
    fail "トラストストアを読み取れない: $TRUSTSTORE_PATH"
    exit 1
fi

# =====================================================================
section "2. トラストストアから PEM バンドルを書き出す (keytool -rfc)"
# =====================================================================
# curl は JKS / PKCS12 を読めないため、PEM に変換して --cacert に渡す。
if [ -z "$KEYTOOL_BIN" ]; then
    fail "keytool が見つからない。-k /path/to/keytool か JAVA_HOME を指定する。"
    exit 1
fi
info "keytool: $KEYTOOL_BIN"

KEYTOOL_OUT="$WORK_DIR/keytool.out"
if [ -n "$TRUSTSTORE_PASSWORD" ]; then
    "$KEYTOOL_BIN" -list -rfc -keystore "$TRUSTSTORE_PATH" -storepass "$TRUSTSTORE_PASSWORD" \
        >"$KEYTOOL_OUT" 2>"$WORK_DIR/keytool.err" </dev/null
    KEYTOOL_RC=$?
else
    # パスワード未指定。JKS は整合性チェックをスキップすれば一覧できる（警告が出る）。
    warn "トラストストアのパスワードが不明。整合性チェック無しで内容のみ読み取る。"
    "$KEYTOOL_BIN" -list -rfc -keystore "$TRUSTSTORE_PATH" \
        >"$KEYTOOL_OUT" 2>"$WORK_DIR/keytool.err" </dev/null
    KEYTOOL_RC=$?
fi

if [ "$KEYTOOL_RC" -ne 0 ]; then
    fail "keytool でトラストストアを読めない（パスワード／ストア種別を確認する）。"
    sed 's/^/       /' "$WORK_DIR/keytool.err" | head -n 10
    exit 1
fi

awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' "$KEYTOOL_OUT" >"$PEM_BUNDLE"
CERT_COUNT="$(grep -c -- '-----BEGIN CERTIFICATE-----' "$PEM_BUNDLE" 2>/dev/null || printf '0')"

if [ "$CERT_COUNT" -gt 0 ]; then
    pass "PEM バンドルを書き出した（証明書 $CERT_COUNT 枚） -> $PEM_BUNDLE"
else
    fail "トラストストアから証明書を取り出せなかった。"
    exit 1
fi

# =====================================================================
section "3. cacert.crt がトラストストアに登録されているか"
# =====================================================================
if [ -z "$CACERT_FILE" ]; then
    skip "cacert.crt が未指定のため、フィンガープリント照合は行わない（-c で指定できる）。"
elif [ ! -r "$CACERT_FILE" ]; then
    fail "cacert.crt を読み取れない: $CACERT_FILE"
elif ! command -v openssl >/dev/null 2>&1; then
    skip "openssl が無いためフィンガープリント照合を行わない。"
else
    # 例: SHA256 Fingerprint=AA:BB:...  -> AA:BB:... だけを取り出す
    CACERT_FP="$(openssl x509 -in "$CACERT_FILE" -noout -fingerprint -sha256 2>/dev/null \
        | sed 's/^.*=//' | tr -d '\r')"
    if [ -z "$CACERT_FP" ]; then
        fail "cacert.crt のフィンガープリントを取得できない（PEM/DER 形式を確認する）。"
    else
        info "cacert.crt SHA-256: $CACERT_FP"
        # PEM バンドル内の各証明書のフィンガープリントと比較する。
        FOUND="no"
        csplit -s -z -f "$WORK_DIR/cert-" -b '%03d.pem' "$PEM_BUNDLE" \
            '/-----BEGIN CERTIFICATE-----/' '{*}' 2>/dev/null
        for pem in "$WORK_DIR"/cert-*.pem; do
            [ -e "$pem" ] || continue
            fp="$(openssl x509 -in "$pem" -noout -fingerprint -sha256 2>/dev/null \
                | sed 's/^.*=//' | tr -d '\r')"
            if [ "$fp" = "$CACERT_FP" ]; then
                FOUND="yes"
                break
            fi
        done
        if [ "$FOUND" = "yes" ]; then
            pass "cacert.crt と同一の証明書がトラストストアに登録されている。"
        else
            fail "cacert.crt はトラストストアに登録されていない。
       keytool -importcert -trustcacerts -noprompt -alias cacert \\
           -file $CACERT_FILE -keystore $TRUSTSTORE_PATH -storepass <password>"
        fi
    fi
fi

# =====================================================================
section "4. curl でトラストストア由来の PEM を使って HTTPS 接続"
# =====================================================================
CURL_COMMON=(--silent --show-error --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME"
             --output /dev/null --write-out '%{http_code}')

HTTP_CODE="$(curl "${CURL_COMMON[@]}" --cacert "$PEM_BUNDLE" "$TARGET_URL" 2>"$WORK_DIR/curl1.err")"
CURL_RC=$?
if [ "$CURL_RC" -eq 0 ]; then
    pass "curl --cacert <トラストストア由来 PEM> で接続成功（HTTP $HTTP_CODE）。"
    info "curl --cacert $PEM_BUNDLE $TARGET_URL"
else
    fail "curl での接続に失敗（exit=$CURL_RC）。"
    sed 's/^/       /' "$WORK_DIR/curl1.err" | head -n 5
    case "$CURL_RC" in
        60) info "exit 60 = 証明書を検証できない。トラストストアに cacert.crt が入っているか確認する。" ;;
        35) info "exit 35 = TLS ハンドシェイク失敗。プロトコル／暗号スイートの不一致を確認する。" ;;
        7)  info "exit 7 = 接続不可。ホスト・ポート・経路（SG / FW）を確認する。" ;;
        6)  info "exit 6 = 名前解決に失敗。" ;;
    esac
fi

# =====================================================================
section "5. curl で cacert.crt を直接指定して HTTPS 接続"
# =====================================================================
if [ -z "$CACERT_FILE" ]; then
    skip "cacert.crt が未指定（-c で指定するとこのテストも実行する）。"
elif [ ! -r "$CACERT_FILE" ]; then
    skip "cacert.crt を読み取れないためスキップ。"
else
    HTTP_CODE2="$(curl "${CURL_COMMON[@]}" --cacert "$CACERT_FILE" "$TARGET_URL" 2>"$WORK_DIR/curl2.err")"
    CURL_RC2=$?
    if [ "$CURL_RC2" -eq 0 ]; then
        pass "curl --cacert $CACERT_FILE で接続成功（HTTP $HTTP_CODE2）。"
    else
        fail "cacert.crt を直接指定した接続に失敗（exit=$CURL_RC2）。"
        sed 's/^/       /' "$WORK_DIR/curl2.err" | head -n 5
        info "サーバ証明書がこの CA 以外で署名されている、またはホスト名が一致しない可能性がある。"
    fi
fi

# =====================================================================
section "6. 対照テスト: 自己署名証明書を渡さないと失敗すること"
# =====================================================================
# ここが失敗（curl exit 60）することで、テスト 4/5 の成功が
# 「OS 標準の CA バンドルではなく、渡した自己署名証明書のおかげ」だと確認できる。
curl "${CURL_COMMON[@]}" "$TARGET_URL" >/dev/null 2>"$WORK_DIR/curl3.err"
CURL_RC3=$?
if [ "$CURL_RC3" -eq 60 ]; then
    pass "--cacert 無しでは検証に失敗した（curl exit 60）。自己署名証明書が効いていることの裏付け。"
elif [ "$CURL_RC3" -eq 0 ]; then
    warn "--cacert 無しでも接続できた。この証明書は OS 標準の CA バンドルにも入っているか、
       サーバ証明書が公的 CA 発行のため、テスト 4/5 はトラストストアの効果を示せていない。"
else
    warn "--cacert 無しの接続が exit=$CURL_RC3 で失敗した（証明書検証以外の理由の可能性）。"
    sed 's/^/       /' "$WORK_DIR/curl3.err" | head -n 5
fi

# =====================================================================
section "7. openssl s_client での検証（参考）"
# =====================================================================
if ! command -v openssl >/dev/null 2>&1; then
    skip "openssl が無いためスキップ。"
else
    HOSTPORT="${TARGET_URL#https://}"
    HOSTPORT="${HOSTPORT%%/*}"
    HOST="${HOSTPORT%%:*}"
    PORT="${HOSTPORT##*:}"
    [ "$PORT" = "$HOST" ] && PORT=443

    VERIFY_LINE="$(openssl s_client -connect "${HOST}:${PORT}" -servername "$HOST" \
        -CAfile "$PEM_BUNDLE" -verify_return_error </dev/null 2>/dev/null \
        | grep -E 'Verify return code' | tail -n 1)"
    if printf '%s' "$VERIFY_LINE" | grep -q '0 (ok)'; then
        pass "openssl s_client -CAfile でも検証成功（$VERIFY_LINE）。"
    elif [ -n "$VERIFY_LINE" ]; then
        fail "openssl s_client での検証に失敗（$VERIFY_LINE）。"
    else
        warn "openssl s_client の結果を取得できなかった。"
    fi
fi

# =====================================================================
section "結果"
# =====================================================================
printf '  PASS=%d  FAIL=%d  WARN=%d  SKIP=%d\n' \
    "$PASS_COUNT" "$FAIL_COUNT" "$WARN_COUNT" "$SKIP_COUNT"

if [ "$FAIL_COUNT" -gt 0 ]; then
    printf '%s判定: NG%s — 上記 [FAIL] の内容を確認する。\n' "$C_RED" "$C_OFF"
    exit 1
fi
printf '%s判定: OK%s — トラストストアの自己署名証明書で curl から HTTPS 接続できている。\n' \
    "$C_GREEN" "$C_OFF"
exit 0
