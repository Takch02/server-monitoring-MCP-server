package com.kakao.kakao_test.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ErrorFingerprint {
    private static final Pattern THROWABLE = Pattern.compile(
            "(?m)^\\h*(?:Caused by:\\h*)?([\\w.$]+(?:Exception|Error))(?=[:\\s]|$)");
    private static final Pattern FRAME = Pattern.compile("(?m)^\\h*at\\h+([\\w.$<>]+)\\(");
    private static final Pattern PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\S*\\s+.*?\\bERROR\\b\\s*");
    private static final Pattern VARIABLE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b|(?<=(?:requestId|route|seq)=)[^\\s,]+|\\b\\d+\\b");

    private ErrorFingerprint() {}

    static String of(String message) {
        String text = message == null ? "" : message;
        Matcher exceptions = THROWABLE.matcher(text);
        String type = null;
        int start = 0;
        while (exceptions.find()) {
            type = exceptions.group(1);
            start = exceptions.end();
        }
        Matcher frames = FRAME.matcher(text).region(start, text.length());
        while (type != null && frames.find()) {
            String method = frames.group(1);
            if (!method.startsWith("java.") && !method.startsWith("jdk.") && !method.startsWith("org.springframework.")) {
                return digest(type + "|" + method);
            }
        }
        String firstLine = text.lines().findFirst().orElse("");
        String body = PREFIX.matcher(firstLine).replaceFirst("");
        // Spring Boot 기본 접두부의 스레드명과 로거 앞 메타데이터 제거
        int separator = body.indexOf(" : ");
        if (!body.equals(firstLine) && separator >= 0) body = body.substring(separator + 3);
        return digest(type + "|" + VARIABLE.matcher(body.strip()).replaceAll("#"));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
