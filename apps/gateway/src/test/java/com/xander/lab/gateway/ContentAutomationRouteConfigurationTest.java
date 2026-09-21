package com.xander.lab.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentAutomationRouteConfigurationTest {

    private final ContentAutomationRouteConfiguration configuration =
            new ContentAutomationRouteConfiguration();

    @Test
    void keepsPublishingCreateOnCoreFallbackUntilTheSwitchIsEnabled() {
        List<Route> routes = routes(false);

        assertThat(routes).extracting(Route::getId)
                .doesNotContain("content-publishing-create");
    }

    @Test
    void registersPublishingCreateAgainstContentAutomationWhenEnabled() {
        List<Route> routes = routes(true);

        Route createRoute = routes.stream()
                .filter(route -> "content-publishing-create".equals(route.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(createRoute.getUri().toString()).isEqualTo("http://content-automation:30006");
    }

    private List<Route> routes(boolean publishingCreateEnabled) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(MethodRoutePredicateFactory.class);
            context.refresh();
            RouteLocatorBuilder builder = new RouteLocatorBuilder(context);
            RouteLocator locator = configuration.contentAutomationRoutes(
                    builder, "http://content-automation:30006", publishingCreateEnabled);
            return locator.getRoutes().collectList().block();
        }
    }
}
