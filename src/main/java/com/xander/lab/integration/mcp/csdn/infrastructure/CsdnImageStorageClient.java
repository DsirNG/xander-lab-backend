package com.xander.lab.integration.mcp.csdn.infrastructure;

/** Stores one external image into CSDN and returns the hosted image URL. */
interface CsdnImageStorageClient {
    String upload(String cookie, String apiKey, String nonce, String signature, String timestamp, String imageUrl, String articleId);
}
