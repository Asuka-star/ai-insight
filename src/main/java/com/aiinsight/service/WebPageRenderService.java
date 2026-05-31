package com.aiinsight.service;

public interface WebPageRenderService {

    RenderResult render(String url);

    static WebPageRenderService disabled() {
        return url -> RenderResult.skipped("renderer disabled");
    }

    record RenderResult(boolean attempted,
                        boolean success,
                        String finalUrl,
                        String title,
                        String html,
                        String note,
                        String failureReason) {

        static RenderResult skipped(String note) {
            return new RenderResult(false, false, "", "", "", note, "SKIPPED");
        }

        static RenderResult success(String finalUrl, String title, String html, String note) {
            return new RenderResult(true, true, finalUrl, title, html, note, "NONE");
        }

        static RenderResult failed(String note, String failureReason) {
            return new RenderResult(true, false, "", "", "", note, failureReason);
        }
    }
}
