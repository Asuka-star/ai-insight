package com.aiinsight.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 PII（个人身份信息）脱敏组件。
 * <p>
 * 对用户访谈、问卷调查等来源的文本进行正则匹配，将敏感信息替换为掩码，
 * 防止 PII 泄露到 LLM 提示词、报告或前端展示中。
 * </p>
 * <p>
 * 支持的 PII 类型：
 * <ul>
 *   <li>中国大陆手机号</li>
 *   <li>电子邮箱</li>
 *   <li>中国大陆身份证号（18位）</li>
 *   <li>IPv4 地址</li>
 *   <li>银行卡号（16-19位）</li>
 * </ul>
 */
@Component
public class PiiDesensitizer {

    /** 中国大陆手机号：1[3-9]开头 + 9位数字 */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![0-9])1[3-9]\\d{9}(?![0-9])");

    /** 电子邮箱：标准 RFC 5322 简化版 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /** 中国大陆身份证号：18位（最后一位可为X/x） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "(?<![0-9])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![0-9])");

    /** IPv4 地址 */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])");

    /** 银行卡号：16-19位连续数字 */
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
            "(?<![0-9])\\d{16,19}(?![0-9])");

    /** 脱敏统计信息 */
    public static class DesensitizeResult {
        private final String text;
        private final List<String> findings;

        public DesensitizeResult(String text, List<String> findings) {
            this.text = text;
            this.findings = List.copyOf(findings);
        }

        public String getText() {
            return text;
        }

        public List<String> getFindings() {
            return findings;
        }

        public boolean hasFindings() {
            return !findings.isEmpty();
        }
    }

    /**
     * 对输入文本执行 PII 脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本；若输入为 null 或空则直接返回
     */
    public String desensitize(String text) {
        return desensitizeWithFindings(text).getText();
    }

    /**
     * 对输入文本执行 PII 脱敏，并返回检测到的敏感信息类型列表。
     *
     * @param text 原始文本
     * @return 包含脱敏后文本和检测发现的 PII 类型
     */
    public DesensitizeResult desensitizeWithFindings(String text) {
        if (text == null || text.isEmpty()) {
            return new DesensitizeResult(text, List.of());
        }

        List<String> findings = new ArrayList<>();
        String result = text;

        // 按优先级顺序替换：先替换更长的模式，避免误匹配。
        // 直接用 replaceAll，通过比较字符串是否变化来判断是否命中。
        // 1. 身份证号（18位，最具体）
        String after = ID_CARD_PATTERN.matcher(result).replaceAll("[身份证号已脱敏]");
        if (!after.equals(result)) { result = after; findings.add("身份证号"); }

        // 2. 银行卡号（16-19位）
        after = BANK_CARD_PATTERN.matcher(result).replaceAll("[银行卡号已脱敏]");
        if (!after.equals(result)) { result = after; findings.add("银行卡号"); }

        // 3. 手机号（11位）
        after = PHONE_PATTERN.matcher(result).replaceAll("[手机号已脱敏]");
        if (!after.equals(result)) { result = after; findings.add("手机号"); }

        // 4. 邮箱
        after = EMAIL_PATTERN.matcher(result).replaceAll("[邮箱已脱敏]");
        if (!after.equals(result)) { result = after; findings.add("邮箱"); }

        // 5. IPv4 地址
        after = IPV4_PATTERN.matcher(result).replaceAll("[IP已脱敏]");
        if (!after.equals(result)) { result = after; findings.add("IP地址"); }

        return new DesensitizeResult(result, List.copyOf(findings));
    }

    /**
     * 检查文本是否包含 PII（不执行替换）。
     *
     * @param text 待检查文本
     * @return 检测到的 PII 类型列表，空列表表示未发现
     */
    public List<String> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> findings = new ArrayList<>();

        if (ID_CARD_PATTERN.matcher(text).find()) {
            findings.add("身份证号");
        }
        if (BANK_CARD_PATTERN.matcher(text).find()) {
            findings.add("银行卡号");
        }
        if (PHONE_PATTERN.matcher(text).find()) {
            findings.add("手机号");
        }
        if (EMAIL_PATTERN.matcher(text).find()) {
            findings.add("邮箱");
        }
        if (IPV4_PATTERN.matcher(text).find()) {
            findings.add("IP地址");
        }

        return findings;
    }
}
