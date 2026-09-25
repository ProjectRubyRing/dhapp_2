package com.example.dhapp.logging;

import java.security.SecureRandom;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * 英大文字・英小文字・数字（[a-zA-Z0-9]）からなるランダム文字列を返す Logback の PropertyDefiner。
 *
 * <p>logback-spring.xml の {@code <define>} から呼ばれ、エラーログのファイル名
 * （{@code <IP>_<ランダム文字列>.err}）に使う。値は Logback の設定読み込み時に 1 回だけ
 * 生成されるため、同じ起動中は同じファイル名に出力され、再起動ごとに別名のファイルになる。</p>
 *
 * <p>長さは {@code <length>} で指定する（既定 10）。</p>
 */
public class RandomStringPropertyDefiner extends PropertyDefinerBase {

    static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private int length = 10;

    public void setLength(int length) {
        this.length = length;
    }

    @Override
    public String getPropertyValue() {
        int n = length > 0 ? length : 10;
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
