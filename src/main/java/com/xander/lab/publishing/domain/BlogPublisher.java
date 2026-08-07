package com.xander.lab.publishing.domain;

public interface BlogPublisher {
    String platform();
    PublishResult publish(PublishCommand command);
}
