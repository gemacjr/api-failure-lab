package com.example.failurelab.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that throws, to prove exception details never reach the response body. */
@RestController
class BoomTestController {

    @GetMapping("/test/boom")
    String boom() {
        throw new IllegalStateException("db password is hunter2");
    }
}
