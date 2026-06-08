package com.aiinsight.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiDesensitizerTest {

    private final PiiDesensitizer desensitizer = new PiiDesensitizer();

    @Test
    void desensitizesChinesePhoneNumber() {
        String input = "用户张三的手机号是13812345678，请尽快联系。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("13812345678");
        assertThat(result).contains("[手机号已脱敏]");
        assertThat(result).contains("张三");
    }

    @Test
    void desensitizesEmailAddress() {
        String input = "请将报告发送至 zhang.san@example.com 或 admin@company.cn。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("zhang.san@example.com");
        assertThat(result).doesNotContain("admin@company.cn");
        assertThat(result).contains("[邮箱已脱敏]");
    }

    @Test
    void desensitizesChineseIdCard() {
        String input = "身份证号：110101199003076534，请核实。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("110101199003076534");
        assertThat(result).contains("[身份证号已脱敏]");
    }

    @Test
    void desensitizesIdCardWithX() {
        String input = "他的身份证是 44010119951215003X，需要验证。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("44010119951215003X");
        assertThat(result).contains("[身份证号已脱敏]");
    }

    @Test
    void desensitizesIpv4Address() {
        String input = "服务器 IP 为 192.168.1.100，请检查日志。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("192.168.1.100");
        assertThat(result).contains("[IP已脱敏]");
    }

    @Test
    void desensitizesBankCardNumber() {
        String input = "银行卡号：6222021234567890123，用于退款。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("6222021234567890123");
        assertThat(result).contains("[银行卡号已脱敏]");
    }

    @Test
    void desensitizesMultiplePiiTypesInSameText() {
        String input = """
                用户访谈记录：
                姓名：李明
                手机：13987654321
                邮箱：liming@test.com
                备注：希望产品能支持更多功能。
                """;
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("13987654321");
        assertThat(result).doesNotContain("liming@test.com");
        assertThat(result).contains("[手机号已脱敏]");
        assertThat(result).contains("[邮箱已脱敏]");
        assertThat(result).contains("李明"); // 姓名不在正则范围内，保留
        assertThat(result).contains("希望产品能支持更多功能");
    }

    @Test
    void preservesTextWithoutPii() {
        String input = "这款产品的用户体验非常好，界面简洁，操作流畅。";
        String result = desensitizer.desensitize(input);

        assertThat(result).isEqualTo(input);
    }

    @Test
    void handlesNullInput() {
        assertThat(desensitizer.desensitize(null)).isNull();
    }

    @Test
    void handlesEmptyInput() {
        assertThat(desensitizer.desensitize("")).isEmpty();
    }

    @Test
    void detectReturnsPiiTypes() {
        String input = "手机：13600001111，邮箱：test@example.com";
        List<String> types = desensitizer.detect(input);

        assertThat(types).contains("手机号", "邮箱");
        assertThat(types).doesNotContain("身份证号", "银行卡号", "IP地址");
    }

    @Test
    void detectReturnsEmptyListForCleanText() {
        List<String> types = desensitizer.detect("这是一段普通的用户反馈，没有敏感信息。");
        assertThat(types).isEmpty();
    }

    @Test
    void desensitizeWithFindingsReturnsBothTextAndFindings() {
        String input = "联系方式：18612345678 或 user@domain.com";
        PiiDesensitizer.DesensitizeResult result = desensitizer.desensitizeWithFindings(input);

        assertThat(result.hasFindings()).isTrue();
        assertThat(result.getFindings()).contains("手机号", "邮箱");
        assertThat(result.getText()).doesNotContain("18612345678");
        assertThat(result.getText()).doesNotContain("user@domain.com");
    }

    @Test
    void doesNotMatchPartialPhoneNumbers() {
        String input = "订单号是1234567890，请查询。";
        String result = desensitizer.desensitize(input);

        // 10位数字不应被误判为手机号
        assertThat(result).isEqualTo(input);
    }

    @Test
    void handlesInterviewTranscript() {
        String input = """
                访谈对象：产品经理王女士
                访谈时间：2024年3月
                痛点：目前使用的竞品工具价格太高，我们团队20人，年费要5万多。
                联系方式：手机15012345678，邮箱wang@example.com
                她提到："如果能便宜30%，我会立刻切换。"
                """;
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("15012345678");
        assertThat(result).doesNotContain("wang@example.com");
        assertThat(result).contains("[手机号已脱敏]");
        assertThat(result).contains("[邮箱已脱敏]");
        assertThat(result).contains("产品经理王女士");
        assertThat(result).contains("便宜30%");
    }

    @Test
    void handlesSurveyOpenFeedback() {
        String input = "希望能增加批量导出功能，我的邮箱是 feedback@user.com，可以联系我详谈。";
        String result = desensitizer.desensitize(input);

        assertThat(result).doesNotContain("feedback@user.com");
        assertThat(result).contains("[邮箱已脱敏]");
        assertThat(result).contains("批量导出功能");
    }
}
