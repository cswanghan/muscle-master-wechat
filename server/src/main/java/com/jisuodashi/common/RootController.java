package com.jisuodashi.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** WeChat CloudBase default probe hits GET /. */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("name", "muscle-master", "status", "UP");
    }
}
