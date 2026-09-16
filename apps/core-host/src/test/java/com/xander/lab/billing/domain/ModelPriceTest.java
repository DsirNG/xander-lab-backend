package com.xander.lab.billing.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceTest {

    @Test
    void mapsPer1kFieldsToColumnsContainingTheSeparatorBefore1k() throws Exception {
        assertColumn("inputMilliPer1kToken", "input_milli_per_1k_token");
        assertColumn("outputMilliPer1kToken", "output_milli_per_1k_token");
        assertColumn("cachedMilliPer1kToken", "cached_milli_per_1k_token");
        assertColumn("cachedWriteMilliPer1kToken", "cached_write_milli_per_1k_token");
    }

    private void assertColumn(String fieldName, String expectedColumn) throws Exception {
        TableField mapping = ModelPrice.class.getDeclaredField(fieldName).getAnnotation(TableField.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).isEqualTo(expectedColumn);
    }
}
