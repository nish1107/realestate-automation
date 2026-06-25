package com.realestateauto.service;

import com.realestateauto.automation.LandRegisterAutomation;
import com.realestateauto.config.AppConfig;

import java.util.function.Consumer;

public class LandRegisterService {

    private final AppConfig config;

    public LandRegisterService(AppConfig config) {
        this.config = config;
    }

    public void download(String address, String savePath, Consumer<String> logger) throws Exception {
        LandRegisterAutomation automation = new LandRegisterAutomation(
            config.get("gov24.id"),
            config.get("gov24.password"),
            savePath,
            logger
        );
        automation.download(address);
    }
}
