package com.knowagent.api.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    @GetMapping("/info")
    Map<String, String> info() {
        return Map.of(
                "name", "KnowAgent",
                "status", "scaffold"
        );
    }
}

