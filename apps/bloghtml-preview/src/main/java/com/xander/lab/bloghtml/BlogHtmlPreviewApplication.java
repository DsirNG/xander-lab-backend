package com.xander.lab.bloghtml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Standalone Spring MVC host for the isolated BlogHTML preview domain. */
@SpringBootApplication
public class BlogHtmlPreviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogHtmlPreviewApplication.class, args);
    }
}
