package com.xander.lab.blogagent.application;

/** Result of the automated compliance gate for a generated article. */
public record ComplianceReview(boolean pass, String reason) {
}