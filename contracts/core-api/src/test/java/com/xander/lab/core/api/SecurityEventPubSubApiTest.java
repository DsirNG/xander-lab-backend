package com.xander.lab.core.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityEventPubSubApiTest {

    @Test
    void pinsPersistedEventTypeVocabulary() {
        assertEquals("AccountDisabled", SecurityEventPubSubApi.ACCOUNT_DISABLED);
        assertEquals("AccountEnabled", SecurityEventPubSubApi.ACCOUNT_ENABLED);
        assertEquals("RefreshTokenReuse", SecurityEventPubSubApi.REFRESH_TOKEN_REUSE);
    }

    @Test
    void pinsStableMessageShape() {
        RecordComponent[] components = SecurityEventPubSubApi.Message.class.getRecordComponents();

        assertArrayEquals(new String[]{"id", "aggregateType", "aggregateId", "eventType", "payload"},
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
    }
}
