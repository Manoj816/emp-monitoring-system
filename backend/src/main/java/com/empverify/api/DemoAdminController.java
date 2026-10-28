package com.empverify.api;

import com.empverify.service.DemoResetService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only admin operations; each is individually guarded by configuration. */
@RestController
@RequestMapping("/api/v1/admin/demo")
public class DemoAdminController {

    private final DemoResetService reset;

    public DemoAdminController(DemoResetService reset) {
        this.reset = reset;
    }

    /** Wipes all demo data (see {@link DemoResetService}). Admin only. */
    @PostMapping("/reset")
    public DemoResetService.Summary reset(Authentication auth) {
        return reset.reset("admin:" + auth.getName());
    }
}
