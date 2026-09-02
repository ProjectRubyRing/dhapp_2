package com.example.dhapp.service;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 検証結果のレポートを<b>コンソール（標準出力）へ UTF-8 で</b>書き出す。
 *
 * <p>{@link System#out} をそのまま使うと {@code stdout.encoding}（JDK 18 以降は
 * ロケール由来の {@code native.encoding}）で符号化されるため、コンテナのロケールが
 * {@code POSIX} / {@code C} だと日本語のレポートが {@code ?} に化ける。
 * Logback の CONSOLE アペンダは {@code <charset>UTF-8</charset>} で出力しているので、
 * コンソールへの直接出力もそれに合わせて UTF-8 に固定する。</p>
 *
 * <p>{@link FileDescriptor#out} を直接包むため、{@code System.setOut()} による
 * 差し替えの影響を受けずに常に実際の標準出力へ書く。</p>
 */
final class ConsoleWriter {

    /** 標準出力への UTF-8 固定ストリーム（行ごとに自動フラッシュ）。 */
    private static final PrintStream CONSOLE =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private ConsoleWriter() {
    }

    /** レポートを 1 ブロックとしてコンソールへ出力する。 */
    static void println(String text) {
        CONSOLE.println(text);
        CONSOLE.flush();
    }
}
