package com.example.dhapp.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/tls/call}（自己署名証明書を使った HTTPS 通信確認）のリクエストボディ。
 *
 * <p>全項目が任意。{@code url} を省略した場合は設定値
 * {@code app.tls.target-url}（環境変数 {@code TLS_TARGET_URL}）が使われる。</p>
 *
 * <p>平文 HTTP では証明書検証が行われず確認にならないため、{@code url} は https に限定する。</p>
 */
public class TlsCallRequest {

    @Pattern(regexp = "(?i)^https://\\S+$",
            message = "https:// で始まる URL を指定してください（TLS の確認 API のため http は不可）")
    private String url;

    @Pattern(regexp = "(?i)^(GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH)$",
            message = "method は GET/POST/PUT/DELETE/HEAD/OPTIONS/PATCH のいずれかを指定してください")
    private String method;

    /** GET 以外のときに送信するリクエストボディ。 */
    @Size(max = 1_048_576, message = "body は 1MB 以下にしてください")
    private String body;

    /** body を送るときの Content-Type。省略時は application/json。 */
    private String contentType;

    public TlsCallRequest() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
