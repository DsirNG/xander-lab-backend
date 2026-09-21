package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Vendor port for CSDN article persistence and external-image hosting. */
public interface PlatformCsdnClient {

    record Credential(String cookie) {
    }

    record Article(String title, String description, String content, List<String> tags,
                   String category, List<String> coverImages) {
    }

    JsonNode saveArticle(Credential credential, Article article);

    JsonNode uploadImage(Credential credential, String articleId, String imageUrl);

    class CsdnApiException extends RuntimeException {
        public CsdnApiException(String message) {
            super(message);
        }

        public CsdnApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class CsdnSessionException extends CsdnApiException {
        public CsdnSessionException(String message) {
            super(message);
        }

        public CsdnSessionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
