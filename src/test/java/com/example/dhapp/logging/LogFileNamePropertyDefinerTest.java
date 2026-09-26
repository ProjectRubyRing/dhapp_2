package com.example.dhapp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.LoggerContext;

/**
 * logback-spring.xml のログファイル名に使う PropertyDefiner（IP プレフィックス・ランダム文字列）の確認。
 */
class LogFileNamePropertyDefinerTest {

    @Test
    void hostIpIsIpv4WithHyphenSeparators() {
        HostIpPropertyDefiner definer = new HostIpPropertyDefiner();
        definer.setContext(new LoggerContext());

        String value = definer.getPropertyValue();

        assertTrue(value.matches("\\d{1,3}-\\d{1,3}-\\d{1,3}-\\d{1,3}"), value);
        assertEquals("10-0-1-23", HostIpPropertyDefiner.toFileNameToken("10.0.1.23"));
    }

    @Test
    void randomStringUsesAlphanumericsOfConfiguredLength() {
        RandomStringPropertyDefiner definer = new RandomStringPropertyDefiner();

        String defaultValue = definer.getPropertyValue();
        assertTrue(defaultValue.matches("[a-zA-Z0-9]{10}"), defaultValue);

        definer.setLength(20);
        String value = definer.getPropertyValue();
        assertTrue(value.matches("[a-zA-Z0-9]{20}"), value);
        assertNotEquals(value, definer.getPropertyValue());
    }

    @Test
    void randomStringUsesConfiguredCharacters() {
        RandomStringPropertyDefiner definer = new RandomStringPropertyDefiner();
        definer.setLength(17);
        definer.setCharacters("0123456789");

        String value = definer.getPropertyValue();
        assertTrue(value.matches("[0-9]{17}"), value);
        assertNotEquals(value, definer.getPropertyValue());
    }
}
