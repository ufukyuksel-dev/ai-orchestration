package com.mbworldwideapps.aiorchestration.modules.scanner;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scanner")
public class ScannerController {

    private final ScannerService scannerService;

    public ScannerController(ScannerService scannerService) {
        this.scannerService = scannerService;
    }

    @PostMapping("/scan")
    public ScanCodebaseResponse scan(@RequestBody ScanCodebaseRequest request) {
        return scannerService.scan(request);
    }
}
