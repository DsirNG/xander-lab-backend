package com.xander.lab.integration.mcp.csdn.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnMarkdownConverterTest {

    @Test
    void toHtmlRendersMarkdownTablesForCsdn() {
        String markdown = """
                # Table Demo

                | Field | Type | Description |
                | --- | --- | --- |
                | id | Long | Primary key |
                | title | String | Post title |
                """;

        String html = CsdnMarkdownConverter.toHtml(markdown);

        assertThat(html).contains("<table>");
        assertThat(html).contains("<thead>");
        assertThat(html).contains("<tbody>");
        assertThat(html).contains("<th>Field</th>");
        assertThat(html).contains("<td>Primary key</td>");
    }
}
