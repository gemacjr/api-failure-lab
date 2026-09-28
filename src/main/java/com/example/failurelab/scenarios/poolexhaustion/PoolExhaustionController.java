package com.example.failurelab.scenarios.poolexhaustion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scenarios/pool-exhaustion")
class PoolExhaustionController {

    private final CallLogService service;

    PoolExhaustionController(CallLogService service) {
        this.service = service;
    }

    @GetMapping("/broken")
    CallLogResult broken() {
        return service.brokenCallAndRecord();
    }

    @GetMapping("/fixed")
    CallLogResult fixed() {
        return service.fixedCallAndRecord();
    }
}
