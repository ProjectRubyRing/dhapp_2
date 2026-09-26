package com.example.dhapp.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.dhapp.dto.ErrorResponse;

/**
 * error.log 出力の検証用 API。
 *
 * POST /api/log/error-test
 *
 * 実際に処理を止めずに（HTTP 500 にせず）、Java の例外スタックトレース形式のエラーログを
 * わざと出力するための機能。GlobalExceptionHandler には伝播させず、この中で捕捉して
 * log.error(msg, throwable) で記録するため、出力先は logback-spring.xml の設定により
 * {@code ${EFS_LOGS_PATH}/<IP>_error.log}（例: {@code 10-0-1-23_error.log}）となる。
 *
 * 生成する例外は「Caused by:」を 2 段含むネストした連鎖にしてあり、
 * 1 つのエラーが複数行（先頭タイムスタンプ + "\tat ..." + "Caused by:" + "... N more"）に
 * 展開される。これにより CloudWatch Agent のマルチライン処理
 * （multi_line_start_pattern = "^\d{4}-\d{2}-\d{2}"）が
 * スタックトレース全体を 1 イベントとしてまとめられることを確認できる。
 */
@RestController
@RequestMapping("/api/log")
public class LogTestController {

    private static final Logger log = LoggerFactory.getLogger(LogTestController.class);

    /**
     * @param count 出力するエラーの件数（既定 1）。複数イベントの区切りを確認したい場合に使う。
     */
    @PostMapping(value = "/error-test", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> errorTest(
            @RequestParam(name = "count", defaultValue = "1") int count) {

        String requestId = UUID.randomUUID().toString();
        int total = Math.max(1, Math.min(count, 100));
        log.info("POST /api/log/error-test received. requestId={}, count={}", requestId, total);

        for (int i = 1; i <= total; i++) {
            try {
                // わざと多段の Caused by チェーンを持つ例外を発生させる。
                throw buildNestedException(requestId, i);
            } catch (RuntimeException e) {
                // 例外オブジェクトを第 2 引数で渡すことで、logback が完全な Java スタックトレース
                // （Caused by / "... N more" を含む）を error.log に複数行で出力する。
                log.error("[error-test] simulated failure for CloudWatch multiline check. requestId={}, seq={}/{}",
                        requestId, i, total, e);
            }
        }

        log.info("POST /api/log/error-test done. requestId={}, emitted={}", requestId, total);
        return ResponseEntity.ok(new ErrorResponse(
                "OK",
                "emitted " + total + " test error(s) to error.log (Java stack trace format)",
                requestId));
    }

    /**
     * 3 階層（DemoException -> IllegalStateException -> java.sql.SQLException 相当の RuntimeException）の
     * 原因連鎖を持つ例外を組み立てる。実運用のスタックトレースに近い多段構造を再現する。
     */
    private static RuntimeException buildNestedException(String requestId, int seq) {
        Throwable rootCause = new IllegalArgumentException(
                "invalid XA resource state (requestId=" + requestId + ", seq=" + seq + ")");
        Throwable midCause = new IllegalStateException(
                "failed to commit branch on DHINFAPXADS", rootCause);
        return new RuntimeException(
                "demo test error #" + seq + " for CloudWatch multiline verification", midCause);
    }
}
