package com.example.dhapp.exception;

/**
 * アプリケーション固有の実行時例外。
 * RuntimeException を継承しているため、JTA/@Transactional のデフォルト挙動でロールバック対象になる。
 */
public class DemoException extends RuntimeException {

    public DemoException(String message) {
        super(message);
    }

    public DemoException(String message, Throwable cause) {
        super(message, cause);
    }
}
