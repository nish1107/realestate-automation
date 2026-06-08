package com.realestateauto.service;

import com.realestateauto.automation.IrosAutomation;
import com.realestateauto.config.AppConfig;

import java.util.function.Consumer;

public class RegistryService {

    private final AppConfig config;

    public RegistryService(AppConfig config) {
        this.config = config;
    }

    public void download(String address, String savePath, Consumer<String> logger) throws Exception {
        IrosAutomation automation = new IrosAutomation(
                config.get("iros.id"),
                config.get("iros.password"),
                config.get("iros.paymentPassword"),
                savePath,
                logger
        );
        automation.download(address);
    }
}
