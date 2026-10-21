package com.gatar.recipedealer.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the phone verify the base URL and its token (the app looks for {@code "ok"} in the body). */
@RestController
public class HealthController {

    @GetMapping("/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
