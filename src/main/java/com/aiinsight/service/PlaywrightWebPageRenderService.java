package com.aiinsight.service;

import com.aiinsight.config.HttpProxyProperties;
import com.aiinsight.config.WebRendererProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class PlaywrightWebPageRenderService implements WebPageRenderService {

    private final WebRendererProperties rendererProperties;
    private final HttpProxyProperties proxyProperties;
    private final AtomicBoolean unavailable = new AtomicBoolean(false);

    public PlaywrightWebPageRenderService(WebRendererProperties rendererProperties,
                                          HttpProxyProperties proxyProperties) {
        this.rendererProperties = rendererProperties;
        this.proxyProperties = proxyProperties;
    }

    @Override
    public RenderResult render(String url) {
        if (!rendererProperties.isEnabled()) {
            return RenderResult.skipped("renderer disabled by configuration");
        }
        if (unavailable.get()) {
            return RenderResult.skipped("renderer unavailable after previous launch failure");
        }
        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setUserAgent("AI-Insight-ResearchBot/0.1")
                        .setIgnoreHTTPSErrors(false);
                try (BrowserContext context = browser.newContext(contextOptions)) {
                    Page page = context.newPage();
                    double timeoutMillis = rendererProperties.getNavigationTimeout().toMillis();
                    page.navigate(url, new Page.NavigateOptions()
                            .setTimeout(timeoutMillis)
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    waitForNetworkIdle(page, timeoutMillis);
                    if (!rendererProperties.getWaitAfterLoad().isZero()
                            && !rendererProperties.getWaitAfterLoad().isNegative()) {
                        page.waitForTimeout(rendererProperties.getWaitAfterLoad().toMillis());
                    }
                    String html = truncateHtml(page.content());
                    return RenderResult.success(
                            page.url(),
                            page.title(),
                            html,
                            "Playwright rendered page with browserChannel=" + browserChannelNote() + "."
                    );
                }
            }
        } catch (RuntimeException ex) {
            String reason = classifyRenderFailure(ex);
            if ("BROWSER_UNAVAILABLE".equals(reason)) {
                unavailable.set(true);
            }
            log.warn("Web page render fallback failed: url={}, reason={}, exceptionType={}, message={}",
                    url,
                    reason,
                    ex.getClass().getName(),
                    ex.getMessage());
            return RenderResult.failed("Playwright render failed: " + ex.getMessage(), reason);
        }
    }

    private Browser launchBrowser(Playwright playwright) {
        List<BrowserType.LaunchOptions> options = new ArrayList<>();
        String channel = rendererProperties.getBrowserChannel();
        if (StringUtils.hasText(channel)) {
            options.add(baseLaunchOptions().setChannel(channel.trim()));
        }
        options.add(baseLaunchOptions());

        RuntimeException lastFailure = null;
        for (BrowserType.LaunchOptions option : options) {
            try {
                return playwright.chromium().launch(option);
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }
        throw lastFailure == null ? new IllegalStateException("No browser launch option was available") : lastFailure;
    }

    private BrowserType.LaunchOptions baseLaunchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(rendererProperties.isHeadless());
        if (proxyProperties != null && proxyProperties.configured()) {
            Proxy proxy = new Proxy("http://" + proxyProperties.resolvedHost() + ":" + proxyProperties.resolvedPort());
            String username = proxyProperties.resolvedUsername();
            String password = proxyProperties.resolvedPassword();
            if (StringUtils.hasText(username)) {
                proxy.setUsername(username);
                proxy.setPassword(password == null ? "" : password);
            }
            options.setProxy(proxy);
        }
        return options;
    }

    private void waitForNetworkIdle(Page page, double timeoutMillis) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeoutMillis));
        } catch (RuntimeException ex) {
            log.debug("Network idle wait skipped after DOM content loaded: message={}", ex.getMessage());
        }
    }

    private String truncateHtml(String html) {
        if (html == null) {
            return "";
        }
        int maxLength = Math.max(1, rendererProperties.getMaxHtmlLength());
        return html.length() <= maxLength ? html : html.substring(0, maxLength);
    }

    private String browserChannelNote() {
        return StringUtils.hasText(rendererProperties.getBrowserChannel())
                ? rendererProperties.getBrowserChannel().trim()
                : "bundled-chromium";
    }

    private String classifyRenderFailure(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            String message = cursor.getMessage() == null ? "" : cursor.getMessage().toLowerCase();
            if (message.contains("executable doesn't exist")
                    || message.contains("browser was not found")
                    || message.contains("looks like playwright was just installed")) {
                return "BROWSER_UNAVAILABLE";
            }
            if (message.contains("net::err_cert") || message.contains("certificate")) {
                return "TLS_FAILED";
            }
            if (message.contains("timeout")) {
                return "TIMEOUT";
            }
            cursor = cursor.getCause();
        }
        return "RENDER_FAILED";
    }
}
