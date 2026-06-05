package com.iflytek.skillhub.controller.support;

/**
 * Resolves package entry content types from filenames for upload and built-in package ingestion.
 */
public final class SkillPackageContentTypeResolver {

    private SkillPackageContentTypeResolver() {
    }

    public static String determineContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/x-yaml";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".js") || lower.endsWith(".cjs") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".ts")) return "text/typescript";
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh")) return "text/x-shellscript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".toml")) return "application/toml";
        return "application/octet-stream";
    }
}
