package com.example.dhapp.logging;

import java.security.SecureRandom;

import ch.qos.logback.core.PropertyDefinerBase;

/**
 * ランダム文字列を返す Logback の PropertyDefiner。既定では英大文字・英小文字・数字（[a-zA-Z0-9]）を使う。
 *
 * <p>logback-spring.xml の {@code <define>} から呼ばれ、エラーログのファイル名
 * （{@code <IP>_<ランダム文字列>.err}、{@code <IP>_asyncdriver_<ランダム数字>.err}）に使う。
 * 値は Logback の設定読み込み時に 1 回だけ生成されるため、同じ起動中は同じファイル名に出力され、
 * 再起動ごとに別名のファイルになる。</p>
 *
 * <p>長さは {@code <length>}（既定 10）、使う文字は {@code <characters>}（既定 [a-zA-Z0-9]）で指定する。
 * 例えば {@code <characters>0123456789</characters>} とすれば数字のみになる。</p>
 */
public class RandomStringPropertyDefiner extends PropertyDefinerBase {

    static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private int length = 10;

    private String characters = CHARACTERS;

    public void setLength(int length) {
        this.length = length;
    }

    public void setCharacters(String characters) {
        this.characters = characters;
    }

    @Override
    public String getPropertyValue() {
        int n = length > 0 ? length : 10;
        String chars = characters != null && !characters.isEmpty() ? characters : CHARACTERS;
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
