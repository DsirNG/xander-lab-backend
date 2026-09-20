package com.xander.lab.agent.host;

import com.xander.lab.common.security.SecurityPlaneConfiguration;
import com.xander.lab.config.MybatisPlusConfig;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wave 2 Agent host bootstrap.
 *
 * <p>The host owns the Agent business boundary. V9 §96 requires the new service to
 * be healthy before Gateway cutover, and a separate bootstrap gives that deployment
 * step a real process without creating a second Agent owner.</p>
 */
@SpringBootApplication(scanBasePackages = "com.xander.lab.agent")
@MapperScan(basePackages = {
        "com.xander.lab.agent.infrastructure",
        "com.xander.lab.agent.tool.mcp.infrastructure"
}, annotationClass = Mapper.class)
@EnableScheduling
@Import({SecurityPlaneConfiguration.class, MybatisPlusConfig.class})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
